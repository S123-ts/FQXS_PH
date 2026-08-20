// src/main/java/org/example/fqxs/repository/NovelRepository.java
package org.example.fqxs.repository;

import org.example.fqxs.entity.Novel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface NovelRepository extends JpaRepository<Novel, Long> {

    List<Novel> findByCategoryNameOrderByRankAsc(String categoryName);

    List<Novel> findByCategoryNameIn(List<String> categoryNames);

    /** ★ 改为返回 Optional */
    Optional<Novel> findByBookId(String bookId);

    @Modifying
    @Transactional
    @Query("DELETE FROM Novel")
    void deleteAllRecords();

    @Modifying
    @Transactional
    @Query("DELETE FROM Novel n WHERE n.categoryName = ?1")
    void deleteByCategoryName(String categoryName);
}