package com.example.chatimg2.service;

import com.example.chatimg2.dto.PageResult;
import com.example.chatimg2.entity.ActivationKey;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.exception.BusinessException;
import com.example.chatimg2.repository.ActivationKeyRepository;
import com.example.chatimg2.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KeyService {

    private static final int FREE_USER_CREDITS = 100;

    private final ActivationKeyRepository keyRepository;
    private final UserAccountRepository userRepository;

    @Transactional
    public PageResult<ActivationKey> getKeysPage(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ActivationKey> result = keyRepository.findAll(pageRequest);
        if (syncUsersForKeys(result.getContent())) {
            result = keyRepository.findAll(pageRequest);
        }

        PageResult<ActivationKey> pageResult = new PageResult<>();
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
        if (user == null || user.getId() == null) {
            return List.of();
        }
        LinkedHashMap<Integer, ActivationKey> merged = new LinkedHashMap<>();
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            keyRepository.findAllByUserEmailOrderByCreatedAtDesc(normalizeEmail(user.getEmail()))
                    .forEach(key -> merged.put(key.getId(), key));
        }
        keyRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId())
                .forEach(key -> merged.putIfAbsent(key.getId(), key));
        return List.copyOf(merged.values());
    }

    @Transactional
    public ActivationKey createKey(Integer totalCredits) {
        if (totalCredits != null && totalCredits < 0) {
            throw businessError("INVALID_CREDITS", "积分必须大于等于 0");
        }
        ActivationKey key = new ActivationKey();
        key.setKeyCode(generateKeyCode());
        key.setTotalCredits(totalCredits != null ? totalCredits : 100);
        return keyRepository.save(key);
    }

    @Transactional
    public ActivationKey updateKeyCredits(Integer id, Integer totalCredits) {
        if (totalCredits == null || totalCredits < 0) {
            throw businessError("INVALID_CREDITS", "积分必须大于等于 0");
        }
        LockedBinding binding = lockKeyAndBoundUser(id);
        ActivationKey key = binding.key();
        if (totalCredits < value(key.getUsedCredits())) {
            throw businessError("INVALID_CREDITS",
                    "总积分不能小于已用积分（" + value(key.getUsedCredits()) + "）");
        }
        key.setTotalCredits(totalCredits);
        keyRepository.save(key);
        if (binding.user() != null) {
            recomputeUserCredits(binding.user());
        }
        return key;
    }

    @Transactional
    public ActivationKey resetUsedCredits(Integer id) {
        LockedBinding binding = lockKeyAndBoundUser(id);
        ActivationKey key = binding.key();
        int refunded = value(key.getUsedCredits());
        key.setUsedCredits(0);
        keyRepository.save(key);

        if (binding.user() != null) {
            UserAccount user = binding.user();
            user.setUsedCredits(Math.max(0, value(user.getUsedCredits()) - refunded));
            userRepository.save(user);
            recomputeUserCredits(user);
        }
        return key;
    }

    @Transactional
    public void deleteKey(Integer id) {
        LockedBinding binding = lockKeyAndBoundUser(id);
        keyRepository.delete(binding.key());
        keyRepository.flush();
        if (binding.user() != null) {
            recomputeUserCredits(binding.user());
        }
    }

    public Optional<ActivationKey> activate(String keyCode) {
        return getByKeyCode(keyCode)
                .filter(key -> key.getStatus() != null && key.getStatus() == 1);
    }

    @Transactional
    public ActivationKey bindToUser(ActivationKey requestedKey, UserAccount requestedUser) {
        if (requestedKey == null || requestedKey.getId() == null
                || requestedUser == null || requestedUser.getId() == null) {
            throw businessError("INVALID_BINDING", "密钥或用户信息无效");
        }

        UserAccount user = lockUser(requestedUser.getId());
        ActivationKey key = lockKey(requestedKey.getId());
        if (key.getUserId() != null && !key.getUserId().equals(user.getId())) {
            throw businessError("KEY_ALREADY_BOUND", "该密钥已绑定其他账号");
        }
        key.setUserId(user.getId());
        key.setUserEmail(normalizeEmail(user.getEmail()));
        keyRepository.save(key);
        recomputeUserCredits(user);
        return key;
    }

    @Transactional
    public UserAccount syncUserCredits(UserAccount user) {
        if (user == null || user.getId() == null) {
            return user;
        }
        UserAccount lockedUser = lockUser(user.getId());
        recomputeUserCredits(lockedUser);
        return lockedUser;
    }

    /**
     * Maps the authoritative user usage onto active bound keys. The user row is
     * always locked before key rows so generation and administrator updates use
     * the same lock order.
     */
    @Transactional
    public Optional<Integer> syncUserUsageToBoundKeys(UserAccount user) {
        if (user == null || user.getId() == null) {
            return Optional.empty();
        }
        UserAccount lockedUser = lockUser(user.getId());
        return distributeUsage(lockedUser, lockBoundKeys(lockedUser));
    }

    private String generateKeyCode() {
        String code;
        do {
            code = "IMG2-" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 8).toUpperCase();
        } while (keyRepository.existsByKeyCode(code));
        return code;
    }

    private LockedBinding lockKeyAndBoundUser(Integer keyId) {
        ActivationKey snapshot = keyRepository.findById(keyId)
                .orElseThrow(this::keyNotFound);
        UserAccount user = resolveBoundUser(snapshot)
                .map(found -> lockUser(found.getId()))
                .orElse(null);
        ActivationKey key = lockKey(keyId);

        if (key.getUserId() != null && (user == null || !key.getUserId().equals(user.getId()))) {
            throw businessError("KEY_BINDING_CHANGED", "密钥绑定状态已变化，请重试");
        }
        return new LockedBinding(key, user);
    }

    private UserAccount lockUser(Integer userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> businessError("USER_NOT_FOUND", "用户不存在"));
    }

    private ActivationKey lockKey(Integer keyId) {
        return keyRepository.findByIdForUpdate(keyId)
                .orElseThrow(this::keyNotFound);
    }

    private void recomputeUserCredits(UserAccount lockedUser) {
        List<ActivationKey> keys = lockBoundKeys(lockedUser);
        int keyCredits = keys.stream()
                .filter(this::isActive)
                .mapToInt(key -> value(key.getTotalCredits()))
                .sum();
        lockedUser.setTotalCredits(FREE_USER_CREDITS + keyCredits);
        userRepository.save(lockedUser);
        distributeUsage(lockedUser, keys);
    }

    private Optional<Integer> distributeUsage(UserAccount user, List<ActivationKey> keys) {
        int remainingUsage = Math.max(0, value(user.getUsedCredits()) - FREE_USER_CREDITS);
        Integer chargedKeyId = null;
        for (ActivationKey key : keys) {
            int nextUsed = isActive(key)
                    ? Math.min(value(key.getTotalCredits()), remainingUsage)
                    : 0;
            int oldUsed = value(key.getUsedCredits());
            if (oldUsed != nextUsed) {
                key.setUsedCredits(nextUsed);
                keyRepository.save(key);
                if (chargedKeyId == null && nextUsed > oldUsed) {
                    chargedKeyId = key.getId();
                }
            }
            remainingUsage = Math.max(0, remainingUsage - nextUsed);
        }
        return Optional.ofNullable(chargedKeyId);
    }

    private List<ActivationKey> lockBoundKeys(UserAccount user) {
        List<ActivationKey> snapshots = getBoundKeysByUser(user);
        List<Integer> lockOrder = snapshots.stream()
                .map(ActivationKey::getId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        Map<Integer, ActivationKey> lockedById = new LinkedHashMap<>();
        for (Integer keyId : lockOrder) {
            keyRepository.findByIdForUpdate(keyId).ifPresent(key -> lockedById.put(keyId, key));
        }

        List<ActivationKey> ordered = new ArrayList<>();
        for (ActivationKey snapshot : snapshots) {
            ActivationKey key = lockedById.get(snapshot.getId());
            if (key == null || !belongsTo(key, user)) {
                continue;
            }
            if (key.getUserId() == null) {
                key.setUserId(user.getId());
                key.setUserEmail(normalizeEmail(user.getEmail()));
                keyRepository.save(key);
            }
            ordered.add(key);
        }
        return ordered;
    }

    private boolean syncUsersForKeys(List<ActivationKey> keys) {
        Set<Integer> syncedUserIds = new HashSet<>();
        boolean changed = false;
        for (ActivationKey key : keys) {
            Optional<UserAccount> user = resolveBoundUser(key);
            if (user.isEmpty() || !syncedUserIds.add(user.get().getId())) {
                continue;
            }
            UserAccount lockedUser = lockUser(user.get().getId());
            int beforeTotal = value(lockedUser.getTotalCredits());
            int beforeUsed = value(lockedUser.getUsedCredits());
            recomputeUserCredits(lockedUser);
            changed |= beforeTotal != value(lockedUser.getTotalCredits())
                    || beforeUsed != value(lockedUser.getUsedCredits());
        }
        return changed;
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
        return userRepository.findByEmail(normalizeEmail(key.getUserEmail()));
    }

    private boolean belongsTo(ActivationKey key, UserAccount user) {
        if (key.getUserId() != null) {
            return key.getUserId().equals(user.getId());
        }
        return key.getUserEmail() != null
                && normalizeEmail(key.getUserEmail()).equals(normalizeEmail(user.getEmail()));
    }

    private boolean isActive(ActivationKey key) {
        return key.getStatus() != null && key.getStatus() == 1;
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private BusinessException keyNotFound() {
        return businessError("KEY_NOT_FOUND", "密钥不存在");
    }

    private BusinessException businessError(String code, String message) {
        return new BusinessException(code, message);
    }

    private record LockedBinding(ActivationKey key, UserAccount user) {
    }
}
