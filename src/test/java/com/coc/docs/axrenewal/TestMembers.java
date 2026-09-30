package com.coc.docs.axrenewal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

/**
 * A member repository for tests whose subject is not membership (ADR-069).
 *
 * <p>Every caller is a member of every project here, so a test about, say,
 * cancelling a run tests cancelling a run — not whether its caller happened
 * to be on the team. What membership itself refuses is {@code
 * AxRenewalMembershipTest}'s subject, with a repository that says no.
 */
final class TestMembers {

    private TestMembers() {}

    static AxRenewalMemberRepository everyone() {
        AxRenewalMemberRepository members = mock(AxRenewalMemberRepository.class);
        when(members.isMember(any(), any())).thenReturn(true);
        when(members.listForProject(any())).thenReturn(List.of());
        return members;
    }
}
