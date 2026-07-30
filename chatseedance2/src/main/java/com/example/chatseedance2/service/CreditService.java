package com.example.chatseedance2.service;

import com.example.chatseedance2.entity.ActivationKey;
import com.example.chatseedance2.entity.UserAccount;
import com.example.chatseedance2.exception.BusinessException;
import com.example.chatseedance2.repository.SeedanceActivationKeyRepository;
import com.example.chatseedance2.repository.SeedanceUserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CreditService {

    private static final int FREE_USER_CREDITS = 100;

    private final SeedanceUserAccountRepository userRepository;
    private final SeedanceActivationKeyRepository keyRepository;

    public UserAccount lockUser(Integer userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "用户不存在"));
    }

    public Integer reserve(UserAccount lockedUser, int credits) {
        if (credits <= 0) {
            throw new BusinessException("INVALID_CREDITS", "任务积分必须大于 0");
        }
        if (lockedUser.getRemainingCredits() < credits) {
            throw new BusinessException("INSUFFICIENT_CREDITS",
                    "积分不足，当前剩余 " + lockedUser.getRemainingCredits());
        }
        lockedUser.setUsedCredits(value(lockedUser.getUsedCredits()) + credits);
        userRepository.save(lockedUser);
        return distributeUsage(lockedUser, lockBoundKeys(lockedUser));
    }

    public void adjust(UserAccount lockedUser, int delta) {
        int current = value(lockedUser.getUsedCredits());
        lockedUser.setUsedCredits(Math.max(0, current + delta));
        userRepository.save(lockedUser);
        distributeUsage(lockedUser, lockBoundKeys(lockedUser));
    }

    private Integer distributeUsage(UserAccount user, List<ActivationKey> keys) {
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
        return chargedKeyId;
    }

    private List<ActivationKey> lockBoundKeys(UserAccount user) {
        Map<Integer, ActivationKey> snapshots = new LinkedHashMap<>();
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            keyRepository.findAllByUserEmailOrderByCreatedAtDesc(user.getEmail().trim().toLowerCase())
                    .forEach(key -> snapshots.put(key.getId(), key));
        }
        keyRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId())
                .forEach(key -> snapshots.putIfAbsent(key.getId(), key));

        List<Integer> lockOrder = snapshots.keySet().stream().sorted().toList();
        Map<Integer, ActivationKey> locked = new LinkedHashMap<>();
        for (Integer keyId : lockOrder) {
            keyRepository.findByIdForUpdate(keyId).ifPresent(key -> locked.put(keyId, key));
        }

        List<ActivationKey> result = new ArrayList<>();
        for (ActivationKey key : locked.values()) {
            if (belongsTo(key, user)) {
                result.add(key);
            }
        }
        return result;
    }

    private boolean belongsTo(ActivationKey key, UserAccount user) {
        if (key.getUserId() != null) {
            return key.getUserId().equals(user.getId());
        }
        return key.getUserEmail() != null
                && key.getUserEmail().trim().equalsIgnoreCase(user.getEmail());
    }

    private boolean isActive(ActivationKey key) {
        return key.getStatus() != null && key.getStatus() == 1;
    }

    private int value(Integer number) {
        return number == null ? 0 : number;
    }
}
