package com.example.chatimg2.service;

import com.example.chatimg2.entity.ActivationKey;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.repository.ActivationKeyRepository;
import com.example.chatimg2.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KeyService {

    private final ActivationKeyRepository keyRepository;
    private final UserAccountRepository userRepository;

    private static final int FREE_USER_CREDITS = 100;

    @Transactional
    public com.example.chatimg2.dto.PageResult<ActivationKey> getKeysPage(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ActivationKey> result = keyRepository.findAll(pageRequest);
        if (syncUsersForKeys(result.getContent())) {
            result = keyRepository.findAll(pageRequest);
        }

        com.example.chatimg2.dto.PageResult<ActivationKey> pageResult = new com.example.chatimg2.dto.PageResult<>();
        pageResult.setList(result.getContent());
        pageResult.setTotal(result.getTotalElements());
        pageResult.setPage(page);
        pageResult.setSize(size);
        pageResult.setTotalPages(result.getTotalPages());
        return pageResult;
    }

    public Optional<ActivationKey> getByKeyCode(String keyCode) {
        if (keyCode == null || keyCode.isBlank()) {
            return Optional.empty();
        }
        return keyRepository.findByKeyCode(keyCode.trim().toUpperCase());
    }

    public List<ActivationKey> getBoundKeysByUser(UserAccount user) {
        if (user == null) {
            return List.of();
        }
        LinkedHashMap<Integer, ActivationKey> merged = new LinkedHashMap<>();
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            String normalizedEmail = normalizeEmail(user.getEmail());
            keyRepository.findAllByUserEmailOrderByCreatedAtDesc(normalizedEmail)
                    .forEach(key -> merged.put(key.getId(), key));
        }
        keyRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId())
                .forEach(key -> merged.putIfAbsent(key.getId(), key));
        return List.copyOf(merged.values());
    }

    public ActivationKey createKey(Integer totalCredits) {
        ActivationKey key = new ActivationKey();
        key.setKeyCode(generateKeyCode());
        key.setTotalCredits(totalCredits != null ? totalCredits : 100);
        return keyRepository.save(key);
    }

    private String generateKeyCode() {
        String code;
        do {
            code = "IMG2-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        } while (keyRepository.existsByKeyCode(code));
        return code;
    }

    @Transactional
    public ActivationKey updateKeyCredits(Integer id, Integer totalCredits) {
        if (totalCredits == null || totalCredits < 0) {
            throw new RuntimeException("积分必须大于等于 0");
        }
        ActivationKey key = keyRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("密钥不存在"));
        if (totalCredits < key.getUsedCredits()) {
            throw new RuntimeException("总积分不能小于已用积分（" + key.getUsedCredits() + "）");
        }
        key.setTotalCredits(totalCredits);
        ActivationKey saved = keyRepository.save(key);
        syncBoundUserCredits(saved);
        return saved;
    }

    @Transactional
    public ActivationKey resetUsedCredits(Integer id) {
        ActivationKey key = keyRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("密钥不存在"));
        int oldUsedCredits = key.getUsedCredits();
        key.setUsedCredits(0);
        ActivationKey saved = keyRepository.save(key);
        refundBoundUserUsedCredits(saved, oldUsedCredits);
        return saved;
    }

    @Transactional
    public void deleteKey(Integer id) {
        ActivationKey key = keyRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("密钥不存在"));
        Optional<UserAccount> boundUser = resolveBoundUser(key);
        keyRepository.deleteById(id);
        boundUser.ifPresent(user -> syncUserCreditsFromBoundKeys(user.getId()));
    }

    public Optional<ActivationKey> activate(String keyCode) {
        if (keyCode == null || keyCode.isBlank()) {
            return Optional.empty();
        }
        Optional<ActivationKey> keyOpt = keyRepository.findByKeyCode(keyCode.trim().toUpperCase());
        if (keyOpt.isPresent() && keyOpt.get().getStatus() != null && keyOpt.get().getStatus() == 1) {
            return keyOpt;
        }
        return Optional.empty();
    }

    @Transactional
    public ActivationKey bindToUser(ActivationKey key, UserAccount user) {
        if (key == null || user == null) {
            return key;
        }
        if (key.getUserId() != null && !key.getUserId().equals(user.getId())) {
            throw new RuntimeException("该密钥已绑定其他账号");
        }
        key.setUserId(user.getId());
        key.setUserEmail(normalizeEmail(user.getEmail()));
        ActivationKey saved = keyRepository.save(key);
        syncUserCreditsFromBoundKeys(user.getId());
        return saved;
    }

    @Transactional
    public Optional<Integer> deductBoundKeyCreditsForUser(UserAccount user, int cost) {
        return syncUserUsageToBoundKeys(user);
    }

    @Transactional
    public UserAccount syncUserCredits(UserAccount user) {
        if (user == null || user.getId() == null) {
            return user;
        }
        syncUserCreditsFromBoundKeys(user.getId());
        return userRepository.findById(user.getId()).orElse(user);
    }

    @Transactional
    public boolean deductCredits(Integer keyId, int cost) {
        ActivationKey key = keyRepository.findById(keyId)
                .orElseThrow(() -> new RuntimeException("密钥不存在"));
        if (key.getRemainingCredits() < cost) {
            return false;
        }
        key.setUsedCredits(key.getUsedCredits() + cost);
        keyRepository.save(key);
        return true;
    }

    @Transactional
    public void refundCredits(Integer keyId, int amount) {
        if (keyId == null || amount <= 0) {
            return;
        }
        ActivationKey key = keyRepository.findById(keyId)
                .orElseThrow(() -> new RuntimeException("密钥不存在"));
        key.setUsedCredits(Math.max(0, key.getUsedCredits() - amount));
        ActivationKey saved = keyRepository.save(key);
        syncBoundUserCredits(saved);
    }

    private void syncBoundUserCredits(ActivationKey key) {
        Optional<UserAccount> user = resolveBoundUser(key);
        if (user.isEmpty()) {
            return;
        }
        syncUserCreditsFromBoundKeys(user.get().getId());
    }

    private boolean syncUsersForKeys(List<ActivationKey> keys) {
        Set<Integer> syncedUserIds = new HashSet<>();
        boolean changed = false;
        for (ActivationKey key : keys) {
            Optional<UserAccount> userOpt = resolveBoundUser(key);
            if (userOpt.isEmpty()) {
                continue;
            }
            UserAccount before = userOpt.get();
            if (!syncedUserIds.add(before.getId())) {
                continue;
            }
            int beforeTotal = before.getTotalCredits() != null ? before.getTotalCredits() : 0;
            int beforeUsed = before.getUsedCredits() != null ? before.getUsedCredits() : 0;
            syncUserCreditsFromBoundKeys(before.getId());
            UserAccount after = userRepository.findById(before.getId()).orElse(before);
            changed = changed
                    || beforeTotal != (after.getTotalCredits() != null ? after.getTotalCredits() : 0)
                    || beforeUsed != (after.getUsedCredits() != null ? after.getUsedCredits() : 0);
        }
        return changed;
    }

    private void syncUserCreditsFromBoundKeys(Integer userId) {
        if (userId == null) {
            return;
        }
        userRepository.findById(userId).ifPresent(user -> {
            int keyCredits = getBoundKeysByUser(user).stream()
                    .filter(key -> key.getStatus() != null && key.getStatus() == 1)
                    .mapToInt(key -> key.getTotalCredits() != null ? key.getTotalCredits() : 0)
                    .sum();
            user.setTotalCredits(FREE_USER_CREDITS + keyCredits);
            userRepository.save(user);
            syncUserUsageToBoundKeys(user);
        });
    }

    @Transactional
    public Optional<Integer> syncUserUsageToBoundKeys(UserAccount user) {
        if (user == null) {
            return Optional.empty();
        }
        int keyUsage = Math.max(0, user.getUsedCredits() - FREE_USER_CREDITS);
        Integer changedKeyId = null;
        for (ActivationKey key : getBoundKeysByUser(user)) {
            if (key.getStatus() == null || key.getStatus() != 1) {
                continue;
            }
            int total = key.getTotalCredits() != null ? key.getTotalCredits() : 0;
            int nextUsed = Math.min(total, keyUsage);
            int oldUsed = key.getUsedCredits() != null ? key.getUsedCredits() : 0;
            if (oldUsed != nextUsed) {
                key.setUsedCredits(nextUsed);
                keyRepository.save(key);
                if (changedKeyId == null && nextUsed > oldUsed) {
                    changedKeyId = key.getId();
                }
            }
            keyUsage = Math.max(0, keyUsage - nextUsed);
        }
        return Optional.ofNullable(changedKeyId);
    }

    private void refundBoundUserUsedCredits(ActivationKey key, int oldUsedCredits) {
        Optional<UserAccount> userOpt = resolveBoundUser(key);
        if (userOpt.isEmpty() || oldUsedCredits <= 0) {
            syncBoundUserCredits(key);
            return;
        }
        UserAccount user = userOpt.get();
        user.setUsedCredits(Math.max(0, user.getUsedCredits() - oldUsedCredits));
        userRepository.save(user);
        syncUserCreditsFromBoundKeys(user.getId());
    }

    private Optional<UserAccount> resolveBoundUser(ActivationKey key) {
        if (key == null) {
            return Optional.empty();
        }
        if (key.getUserId() != null) {
            Optional<UserAccount> user = userRepository.findById(key.getUserId());
            if (user.isPresent()) {
                return user;
            }
        }
        if (key.getUserEmail() == null || key.getUserEmail().isBlank()) {
            return Optional.empty();
        }
        Optional<UserAccount> user = userRepository.findByEmail(normalizeEmail(key.getUserEmail()));
        user.ifPresent(found -> {
            if (key.getUserId() == null || !key.getUserId().equals(found.getId())) {
                key.setUserId(found.getId());
                key.setUserEmail(normalizeEmail(found.getEmail()));
                keyRepository.save(key);
            }
        });
        return user;
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
