package com.github.shikejing.gamerewardoraclejava.service;

import com.github.shikejing.gamerewardoraclejava.model.Claim;
import com.github.shikejing.gamerewardoraclejava.repository.ClaimRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 独立 Bean：以 REQUIRES_NEW 事务执行领取插入。
 * 并发下唯一约束冲突只回滚嵌套事务，不影响外层领取事务。
 */
@Component
public class ClaimInserter {

    private final ClaimRepository claimRepository;

    public ClaimInserter(ClaimRepository claimRepository) {
        this.claimRepository = claimRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim insert(Claim claim) {
        return claimRepository.saveAndFlush(claim);
    }
}
