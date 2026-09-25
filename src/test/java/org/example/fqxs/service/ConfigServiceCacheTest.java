package org.example.fqxs.service;

import org.example.fqxs.entity.AppConfig;
import org.example.fqxs.repository.AppConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配置缓存的钉子：读走内存不打库，写入后缓存失效并在下次读取时重建。
 * 用受控的内存 Map 仓库，才能数清 findAll 的次数。
 */
@ExtendWith(MockitoExtension.class)
class ConfigServiceCacheTest {

    private final Map<String, AppConfig> store = new HashMap<>(Map.of(
            new AppConfig(ConfigService.KEY_RANK_TYPE, "read").getCfgKey(),
            new AppConfig(ConfigService.KEY_RANK_TYPE, "read"),
            new AppConfig(ConfigService.KEY_DISPLAY_CATEGORIES,
                    ConfigService.DEFAULT_DISPLAY_CATEGORIES).getCfgKey(),
            new AppConfig(ConfigService.KEY_DISPLAY_CATEGORIES, ConfigService.DEFAULT_DISPLAY_CATEGORIES)));

    @Test
    void readsGoThroughTheCacheUntilAWriteInvalidatesIt() {
        AppConfigRepository repository = controlledRepository();
        ConfigService service = new ConfigService(repository);

        assertEquals("read", service.getActiveRankType());
        assertEquals("read", service.getActiveRankType());
        verify(repository, times(1)).findAll();

        service.updateConfig("new", null);

        // 写后失效重建：下次读取拿到的必须是新值，且只再打一次库
        assertEquals("new", service.getActiveRankType());
        assertEquals(1, service.getActiveRankMold());
        verify(repository, times(2)).findAll();
    }

    private AppConfigRepository controlledRepository() {
        AppConfigRepository repository = mock(AppConfigRepository.class);
        when(repository.findAll()).thenAnswer(invocation -> List.copyOf(store.values()));
        // 本用例不触发播种路径，宽松处理
        lenient().when(repository.existsById(anyString()))
                .thenAnswer(invocation -> store.containsKey(invocation.getArgument(0)));
        when(repository.save(any())).thenAnswer(invocation -> {
            AppConfig config = invocation.getArgument(0);
            store.put(config.getCfgKey(), config);
            return config;
        });
        return repository;
    }
}
