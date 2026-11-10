package br.com.lcmtech.optics.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.lcmtech.optics.identity.domain.TeamGovernance.Member;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeamGovernanceTest {
    private final Member primary = new Member(UUID.randomUUID(), "MASTER", true);
    private final Member secondMaster = new Member(UUID.randomUUID(), "MASTER", false);
    private final Member otherMaster = new Member(UUID.randomUUID(), "MASTER", false);
    private final Member seller = new Member(UUID.randomUUID(), "SELLER", false);

    @Test
    void principalGerenciaTodosMenosASiMesmo() {
        assertThat(TeamGovernance.canManage(primary, secondMaster)).isTrue();
        assertThat(TeamGovernance.canManage(primary, seller)).isTrue();
        assertThat(TeamGovernance.canManage(primary, primary)).isFalse();
    }

    @Test
    void ninguemAlteraOPrincipal() {
        assertThat(TeamGovernance.canManage(secondMaster, primary)).isFalse();
        assertThatThrownBy(() -> TeamGovernance.requireRoleChange(secondMaster, primary, "SELLER"))
                .hasMessageContaining("principal");
    }

    @Test
    void masterSecundarioGerenciaApenasQuemNaoEAdministrador() {
        assertThat(TeamGovernance.canManage(secondMaster, seller)).isTrue();
        assertThat(TeamGovernance.canManage(secondMaster, otherMaster)).isFalse();
        assertThatThrownBy(() -> TeamGovernance.requireRoleChange(secondMaster, seller, "MASTER"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TeamGovernance.requireInvite(secondMaster, "MASTER"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ninguemAlteraOProprioAcesso() {
        assertThatThrownBy(() -> TeamGovernance.requireRoleChange(secondMaster, secondMaster, "SELLER"))
                .hasMessageContaining("próprio");
    }

    @Test
    void administradorNaoTemAcessosIndividuais() {
        assertThatThrownBy(() -> TeamGovernance.requirePermissionChange(primary, secondMaster))
                .hasMessageContaining("acesso completo");
        TeamGovernance.requirePermissionChange(secondMaster, seller);
    }

    @Test
    void naoGerentesNaoGerenciamNinguem() {
        assertThat(TeamGovernance.canManage(seller, new Member(UUID.randomUUID(), "SECRETARY", false))).isFalse();
    }
}
