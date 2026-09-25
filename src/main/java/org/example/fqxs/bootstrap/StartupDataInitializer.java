package org.example.fqxs.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.constant.CategoryCatalog;
import org.example.fqxs.repository.NovelRepository;
import org.example.fqxs.service.ConfigService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 启动收尾，全部幂等（重复启动无副作用，跑两遍结果一致）：
 * 1. 历史数据回填：category_code 尚为 NULL 的行按 category_name 对男频目录的 19 个名字
 *    映射出 code（现库历史上只有男频榜；含曾退役又复出的“都市修真”=124）。
 *    映射不到的乱数据保持 NULL，即不出现在任何分类视图里，等人工处理。
 *    gender 列不用回填：加列时的 DEFAULT 1 已把历史行落成男频。
 * 2. 配置播种：app_config 里缺哪个键就补哪个键的默认值，已有配置（含空串）不动。
 */
@Slf4j
@Component
@Order(100) // 必须晚于 SchemaInitializer（建表先行，回填/播种才有表）
@RequiredArgsConstructor
public class StartupDataInitializer implements ApplicationRunner {

    private final NovelRepository novelRepository;
    private final ConfigService configService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void run(ApplicationArguments args) {
        // ApplicationRunner 抛异常会终止整个启动，而“数据库不可用时应用要照常起来、
        // 读接口 503”是 HANDOVER §3 的契约；回填与播种全部幂等，这次失败下次启动自动补上。
        // Tomcat 先于 runner 开始监听，窗口内 meta 可能拿到未回填的空配置，完成后缓存自愈。
        try {
            // @Modifying JPQL 需要事务包裹（HANDOVER §12），不能只靠方法上的 @Transactional：
            // 事务开启本身在库不可用时就抛，try/catch 必须罩住整个事务
            transactionTemplate.executeWithoutResult(tx -> {
                backfillCategoryCodes();
                configService.seedDefaults();
            });
        } catch (Exception e) {
            log.error("启动初始化失败（数据库不可用？），已跳过，下次启动重试: {}", e.getMessage());
        }
    }

    private void backfillCategoryCodes() {
        int total = 0;
        // 只对男频目录回填：女频与男频共用部分名字（悬疑脑洞等），历史行全是男频榜
        for (CategoryCatalog category : CategoryCatalog.values()) {
            if (category.getGender() != 1) {
                continue;
            }
            total += novelRepository.backfillCategoryCode(category.getName(), category.getCode());
        }
        if (total > 0) {
            log.info("历史数据回填完成: {} 行补上 category_code", total);
        }
    }
}
