package org.example.fqxs.repository;

import org.example.fqxs.entity.AppConfig;
import org.springframework.data.jpa.repository.JpaRepository;

/** cfg_key 是主键，findAll 一次取全量即可（键数量固定且很少）。 */
public interface AppConfigRepository extends JpaRepository<AppConfig, String> {
}
