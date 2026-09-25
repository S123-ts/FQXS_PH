package org.example.fqxs.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.example.fqxs.util.DateUtil;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 运行时配置键值对（rank_type / display_categories），结构由 db/schema-h2.sql 维护。
 * 值一律存字符串：rank_type 是 "read"/"new"，display_categories 是逗号分隔的 code 串。
 */
@Getter
@Setter
@Entity
@Table(name = "app_config")
@NoArgsConstructor
public class AppConfig {

    @Id
    @Column(name = "cfg_key", nullable = false, length = 100)
    private String cfgKey;

    @Column(name = "cfg_value", nullable = false, length = 4000)
    private String cfgValue;

    @Column(name = "update_time_db")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTimeDb;

    public AppConfig(String cfgKey, String cfgValue) {
        this.cfgKey = cfgKey;
        this.cfgValue = cfgValue;
    }

    @PrePersist
    protected void onCreate() {
        updateTimeDb = DateUtil.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updateTimeDb = DateUtil.now();
    }
}
