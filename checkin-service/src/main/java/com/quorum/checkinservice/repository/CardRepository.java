package com.quorum.checkinservice.repository;

import com.quorum.checkinservice.entity.Card;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CardRepository extends JpaRepository<Card, String> {

    List<Card> findAllByOrderByStudentIdAscCardUidAsc();

    List<Card> findByStudentIdOrderByCardUidAsc(String studentId);
}
