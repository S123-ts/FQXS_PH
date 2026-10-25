package org.example.fqxs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.client.ThirdPartyBookClient;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.AuthorMeta;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.exception.ItemUnavailableException;
import org.example.fqxs.exception.LineCoolingException;
import org.example.fqxs.logging.BookLogContext;
import org.example.fqxs.repository.AuthorMetaRepository;
import org.example.fqxs.repository.NovelRepository;
import org.example.fqxs.util.DateUtil;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 作者性别 / 评分的补全，两条路径共用一把锁：
 *
 * <ol>
 *   <li>手动小批量：只补当前榜单热度前 N 本（默认 50），单次最多 {@code book-meta-batch-max} 本
 *       （默认上限 10，前端默认 5），严格串行、每本固定间隔（默认 2 秒），连续失败 3 本就停；</li>
 *   <li>启动后台补齐：{@code BookMetaBackfillRunner} 开守护线程，不限前 N、按待补队列的
 *       热度序逐本推进，每本间隔 {@code book-meta-backfill-per-hour} 换算出的节拍
 *       （默认 60 本/小时 = 一分钟一本），抢不到锁就让路。</li>
 * </ol>
 *
 * <p>第三方 /info 是共享云手机、约 19KB/本，容量很小，两条路径都严格串行。性别只取一次
 * （有 checked_time 就不再请求）；评分按 {@code rating-refresh-days} 刷新（默认 5 天）。</p>
 *
 * <p><b>性别兜底（2026-10-25 用户要求）</b>：第三方判未知（{@code profile_gender}=2 或缺失）时，
 * 按书籍频道记性别——男频记男、女频记女，让页面上不再出现「未知」。第三方给了可信值（0/1）时
 * 绝不拿频道覆盖（女写男频、男写女频都常见，频道不是性别判据）。兜底取 {@code novel_rank.gender}，
 * 书已掉榜无从兜底时仍写未知。详见 {@link #authorGenderOf}。</p>
 *
 * <p>结果落在 {@code author_meta}（跟着 book_id 走，不跟榜单走）：书掉榜被清理时榜单行会删掉，
 * 但作者信息留在元数据表里，重新上榜由拉榜回填直接命中，不必再花一次额度。
 * 同一本书在多个分类各有一行，同步时按 bookId 全行联动。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookMetaEnricher {

    /** 连续失败多少本停止本次补全：线路挂了时继续打只会更糟。 */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final NovelRepository novelRepository;
    private final AuthorMetaRepository authorMetaRepository;
    private final ThirdPartyBookClient bookClient;
    private final AiDetectConfig aiDetectConfig;
    private final ConfigService configService;

    /** 同一时刻只允许一个补全任务，避免连点把线路打满。 */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 本次补全结果。
     *
     * @param updated   成功更新几本
     * @param failed    失败几本（线路/未知错误）
     * @param skipped   书级失败几本（已下架，已盖章离开待补队列）
     * @param remaining 前 N 本中仍待补的数量
     * @param requested 本次计划请求几本
     */
    public record Result(int updated, int failed, int skipped, long remaining, int requested) {
    }

    /** 后台补齐的单本结果：UPDATED=补到落库，FAILED=请求失败，SKIPPED=书已下架，COOLDOWN=线路在冷却，LOCKED=锁被手动路径占着。 */
    public enum Outcome {
        UPDATED, FAILED, SKIPPED, COOLDOWN, LOCKED
    }

    /**
     * 补一小批作者信息。
     *
     * @param limit    本次最多几本；服务端再钳到配置上限
     * @param rankType 当前榜单分区（read/new）
     * @return 本次结果
     * @throws IllegalStateException    已有任务在进行
     * @throws IllegalArgumentException 第三方通道未启用
     */
    public Result enrich(int limit, String rankType) {
        if (!aiDetectConfig.isThirdPartyEnabled()) {
            throw new IllegalArgumentException("第三方通道未启用，无法补作者信息");
        }
        int batch = Math.max(1, Math.min(limit, Math.max(1, aiDetectConfig.getBookMetaBatchMax())));
        if (!lock.tryLock()) {
            throw new IllegalStateException("正在补充作者信息，请稍候再试");
        }
        try {
            List<String> pending = pendingTopBookIds(rankType);
            List<String> targets = pending.stream().limit(batch).toList();
            int updated = 0;
            int failed = 0;
            int skipped = 0;
            int consecutiveFailures = 0;
            for (int i = 0; i < targets.size(); i++) {
                String bookId = targets.get(i);
                try {
                    ThirdPartyBookClient.BookMeta meta = bookClient.fetchMeta(bookId);
                    updateRows(bookId, meta);
                    updated++;
                    consecutiveFailures = 0;
                } catch (ItemUnavailableException itemFailure) {
                    // 书已下架：盖章离开待补队列，不算线路失败，也不能连累后面的书
                    markMetaUnavailable(bookId);
                    skipped++;
                    consecutiveFailures = 0;
                    log.info("作者信息跳过（书已不可用）: bookId={}, 原因={}",
                            bookId, itemFailure.getMessage());
                } catch (LineCoolingException cooling) {
                    log.warn("第三方线路冷却中，停止本次补全: {}", cooling.getMessage());
                    break;
                } catch (RuntimeException failure) {
                    failed++;
                    consecutiveFailures++;
                    log.warn("作者信息补全失败: bookId={}, 原因={}", bookId, failure.getMessage());
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        log.warn("连续 {} 本失败，停止本次补全（线路可能不可用）", consecutiveFailures);
                        break;
                    }
                }
                if (i < targets.size() - 1) {
                    sleepBetweenRequests();
                }
            }
            // 重新查一次，失败的书仍算待补，数字才对得上
            long remaining = pendingTopBookIds(rankType).size();
            log.info("作者信息补全完成: 更新 {} / 跳过 {} / 失败 {} / 待补 {} / 本次请求 {}", updated, skipped, failed,
                    remaining, targets.size());
            return new Result(updated, failed, skipped, remaining, targets.size());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 全库待补的书 id（按热度降序，即页面「全部」总榜的顺序）：性别没取过，或评分没取过/已过刷新周期。
     * 后台补齐按它逐本推进。
     *
     * <p>范围跟着拉取频道走：自用只拉男频时，第三方额度只花在男频书上，
     * 否则会把额度平均撒给女频、男频前排反而补齐得更慢。</p>
     */
    public List<String> pendingMetaBookIds() {
        LocalDateTime threshold = DateUtil.now()
                .minusDays(Math.max(1, aiDetectConfig.getRatingRefreshDays()));
        return novelRepository.findBookIdsNeedingMeta(threshold,
                ConfigService.genderCodeOf(configService.getFetchGender()));
    }

    /**
     * 补一本（后台补齐线程用）。抢不到锁说明手动「补作者信息」正在跑，返回让路而不是报错；
     * 失败原因在方法内落日志，调用方只拿结果计数。
     */
    public Outcome enrichOne(String bookId) {
        // 圈定这本书：这条链路上的日志（含第三方 /info 抽取）都归到它名下
        try (BookLogContext ignored = BookLogContext.open(bookId)) {
            if (!lock.tryLock()) {
                return Outcome.LOCKED;
            }
            try {
                ThirdPartyBookClient.BookMeta meta = bookClient.fetchMeta(bookId);
                updateRows(bookId, meta);
                return Outcome.UPDATED;
            } catch (LineCoolingException cooling) {
                // 线路只是歇着，不是坏了：不记失败，让线程等到冷却结束
                return Outcome.COOLDOWN;
            } catch (ItemUnavailableException itemFailure) {
                markMetaUnavailable(bookId);
                log.info("作者信息跳过（书已不可用）: bookId={}, 原因={}", bookId, itemFailure.getMessage());
                return Outcome.SKIPPED;
            } catch (RuntimeException failure) {
                log.warn("作者信息补全失败: bookId={}, 原因={}", bookId, failure.getMessage());
                return Outcome.FAILED;
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 书级失败也要落检查时间：否则它会永远留在待补队列里，每隔一阵被重新抽中、
     * 再触发一次注定失败的请求。评分同样按刷新周期再来（默认 5 天）。
     *
     * <p>盖章盖在 author_meta 上：待补判据已经换成元数据表，榜单行上盖不盖章都不影响排队，
     * 省掉一次逐行回写。</p>
     */
    private void markMetaUnavailable(String bookId) {
        LocalDateTime now = DateUtil.now();
        AuthorMeta record = authorMetaRepository.findById(bookId).orElseGet(() -> newRecord(bookId));
        if (record.getGenderCheckedTime() == null) {
            record.setGenderCheckedTime(now);
        }
        if (record.getRatingCheckedTime() == null) {
            record.setRatingCheckedTime(now);
        }
        authorMetaRepository.save(record);
    }

    /** 前 N 本里还需要请求的书（只回 bookId）：元数据表没记录，或性别没取过，或评分没取过/已过期。 */
    private List<String> pendingTopBookIds(String rankType) {
        LocalDateTime threshold = DateUtil.now()
                .minusDays(Math.max(1, aiDetectConfig.getRatingRefreshDays()));
        int topRank = Math.max(1, aiDetectConfig.getBookMetaTopRank());
        int genderFilter = ConfigService.genderCodeOf(configService.getFetchGender());
        List<String> topIds = novelRepository.findMetaTopBooks(rankType, PageRequest.of(0, topRank)).stream()
                // 跟着拉取频道走，与后台补齐同一口径
                .filter(novel -> genderFilter < 0
                        || (novel.getGender() != null && novel.getGender() == genderFilter))
                .map(Novel::getBookId)
                .filter(bookId -> bookId != null && !bookId.isBlank())
                .distinct()
                .toList();
        if (topIds.isEmpty()) {
            return List.of();
        }
        // 一次 IN 预读，避免逐本查元数据表
        Map<String, AuthorMeta> known = authorMetaRepository.findAllByBookIdIn(topIds).stream()
                .collect(Collectors.toMap(AuthorMeta::getBookId, meta -> meta, (first, second) -> first));
        return topIds.stream().filter(bookId -> needsMeta(known.get(bookId), threshold)).toList();
    }

    /** 这本书还要不要请求：没记录肯定要；性别没取过要；评分没取过或已过期要。 */
    private static boolean needsMeta(AuthorMeta meta, LocalDateTime threshold) {
        if (meta == null) {
            return true;
        }
        if (meta.getGenderCheckedTime() == null) {
            return true;
        }
        return meta.getRatingCheckedTime() == null || meta.getRatingCheckedTime().isBefore(threshold);
    }

    /**
     * 把补齐结果落库：先写 author_meta（权威、跟书走），再同步到该 bookId 的榜单行（页面读这里）。
     *
     * <p>两边都写是因为职责不同：元数据表保证「取过一次就永不丢」，榜单行保证页面筛选排序不必 join。
     * 只写一边都会坏——只写元数据页面看不到；只写榜单行，书一掉榜就白取。</p>
     *
     * <p>成功后打一行 INFO，记录实际落库值：日志是事后追查"某本补成了什么"的唯一线索
     * （补齐线程本身只记批次汇总，不记逐本结果）。</p>
     */
    private void updateRows(String bookId, ThirdPartyBookClient.BookMeta meta) {
        // 先取榜单行：作者名与书名只有这里才有，元数据表存的是补齐后的快照
        List<Novel> rows = novelRepository.findAllByBookId(bookId);
        LocalDateTime now = DateUtil.now();

        AuthorMeta record = authorMetaRepository.findById(bookId).orElseGet(() -> newRecord(bookId));
        if (record.getAuthor() == null && !rows.isEmpty()) {
            record.setAuthor(rows.get(0).getAuthor());
        }
        // 性别只取一次：已经取过就保留首次结果，评分刷新时也不覆盖
        boolean genderWritten = record.getGenderCheckedTime() == null;
        if (genderWritten) {
            record.setGender(authorGenderOf(meta, rows));
            record.setGenderCheckedTime(now);
        }
        if (meta.rating() != null) {
            record.setRating(meta.rating());
        }
        record.setRatingCheckedTime(now);
        authorMetaRepository.save(record);

        syncRankRows(rows, record);
        // 兜底来的性别要在日志里能看出来：事后追查"某本为什么是男/女"时，
        // 分不清"第三方说的"和"按频道猜的"就等于没有线索
        String genderNote = !genderWritten ? "（沿用首次结果）"
                : meta.authorGender() == null && record.getGender() != null ? "（按书籍频道兜底）" : "";
        log.info("作者信息已落库: bookId={}, 书名={}, 作者={}, 性别={}{}, 评分={}",
                bookId,
                rows.isEmpty() ? "（榜单已无此书，元数据已留存）" : rows.get(0).getTitle(),
                record.getAuthor() == null ? "未知" : record.getAuthor(),
                genderLabel(record.getGender()),
                genderNote,
                record.getRating() == null ? "无" : record.getRating());
    }

    /**
     * 作者性别取值：第三方给了可信值（{@code profile_gender} 0/1）就用它；
     * 第三方判未知（2 或字段缺失）时按书籍频道兜底——男频记男、女频记女。
     *
     * <p><b>频道兜底是产品取舍，不是数据结论。</b>作者性别与书籍频道并不强相关
     * （女写男频、男写女频都常见，2026-10-25 实测过，因此
     * {@code AUTHOR_GENDER <> GENDER} 从来不能当"错值"判据）；但用户明确要求
     * 页面上不再出现「未知」这一档，且第三方对这类书的作者主页普遍资料全空
     * （无百科、0 粉丝、0 作品），短期拿不到更可信的值，两害相权取其轻。</p>
     *
     * <p>兜底值取 {@code novel_rank.gender}（程序按拉取频道自写，1=男频 / 0=女频，
     * 与作者性别编码恰好同值），不取第三方顶层 {@code gender}——那个字段在响应里
     * 被正文字段淹着、定位不稳，且同样是频道语义。榜单行已掉（rows 为空）时
     * 无从兜底，仍写未知。</p>
     */
    private static Integer authorGenderOf(ThirdPartyBookClient.BookMeta meta, List<Novel> rows) {
        if (meta.authorGender() != null) {
            return meta.authorGender();
        }
        if (rows.isEmpty()) {
            return null;
        }
        Integer channel = rows.get(0).getGender();
        return channel != null && (channel == 0 || channel == 1) ? channel : null;
    }

    /** 元数据同步到榜单行：性别只在行上还没盖过章时写，评分有值就覆盖。 */
    private void syncRankRows(List<Novel> rows, AuthorMeta record) {
        if (rows.isEmpty()) {
            return;
        }
        for (Novel row : rows) {
            if (row.getAuthorGenderCheckedTime() == null && record.getGenderCheckedTime() != null) {
                row.setAuthorGender(record.getGender());
                row.setAuthorGenderCheckedTime(record.getGenderCheckedTime());
            }
            if (record.getRating() != null) {
                row.setRating(record.getRating());
            }
            if (record.getRatingCheckedTime() != null) {
                row.setRatingCheckedTime(record.getRatingCheckedTime());
            }
        }
        novelRepository.saveAll(rows);
    }

    /** 新建一条空元数据：主键先填好，后续字段由调用方补齐。 */
    private static AuthorMeta newRecord(String bookId) {
        AuthorMeta meta = new AuthorMeta();
        meta.setBookId(bookId);
        return meta;
    }

    /** 0=女 / 1=男 / null=未知，供日志用。 */
    private static String genderLabel(Integer authorGender) {
        if (authorGender == null) {
            return "未知";
        }
        return authorGender == 1 ? "男" : authorGender == 0 ? "女" : "未知";
    }

    private void sleepBetweenRequests() {
        long interval = Math.max(0, aiDetectConfig.getBookMetaIntervalMillis());
        if (interval == 0) {
            return;
        }
        try {
            Thread.sleep(interval);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
