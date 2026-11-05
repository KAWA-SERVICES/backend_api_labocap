package com.labo.anapath.common.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DemandeFluidChatRepository extends JpaRepository<DemandeFluidChat, UUID> {

    Optional<DemandeFluidChat> findByTypeAndCibleIdAndAbandonneeLeIsNull(DemandeFluidChat.Type type, UUID cibleId);

    List<DemandeFluidChat> findByTypeAndAbandonneeLeIsNullOrderByUpdatedAtAsc(DemandeFluidChat.Type type, Pageable page);
}
