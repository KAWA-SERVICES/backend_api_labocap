package com.labo.anapath.setting;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SettingAppRepository extends JpaRepository<SettingApp, UUID> {
    Page<SettingApp> findByBranchId(UUID branchId, Pageable pageable);
    Optional<SettingApp> findByKeyAndBranchId(String key, UUID branchId);

    /**
     * Charge un lot de settings par clés, toutes branches confondues, du plus ancien
     * au plus récent. Alimente {@link PublicBrandingController} : sans utilisateur
     * authentifié aucune branche n'est connue, et le tri par ancienneté rend le choix
     * déterministe (la branche mère l'emporte) là où {@code findByKey} lèverait une
     * {@code IncorrectResultSizeDataAccessException} dès que deux branches définissent
     * la même clé.
     *
     * @param keys clés recherchées
     * @return les lignes correspondantes, triées par date de création croissante
     */
    List<SettingApp> findByKeyInOrderByCreatedAtAsc(Collection<String> keys);

    /**
     * Recherche un setting global par sa clé (sans filtre sur la branche).
     * Utilisé pour les settings transversaux (OurVoice, entête PDF…).
     */
    Optional<SettingApp> findByKey(String key);

    /**
     * Valeur la plus récemment enregistrée d'une clé, toutes branches confondues :
     * pour un réglage commun au laboratoire, c'est la dernière saisie qui compte,
     * quelle que soit l'agence depuis laquelle elle a été faite.
     */
    Optional<SettingApp> findFirstByKeyOrderByUpdatedAtDesc(String key);
}
