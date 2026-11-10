package br.com.lcmtech.optics.identity.domain;

import java.util.List;
import java.util.UUID;

/**
 * Quem pode gerenciar quem na equipe de uma ótica.
 *
 * <ul>
 *   <li>O Master principal (quem criou a ótica) nunca é removido, rebaixado nem tem os acessos
 *       alterados por outra pessoa.</li>
 *   <li>Somente o Master principal cria, promove, rebaixa ou remove outros administradores master.</li>
 *   <li>Qualquer administrador master gerencia gerentes, secretários(as) e vendedores(as).</li>
 *   <li>Ninguém altera o próprio acesso pela tela de equipe.</li>
 *   <li>Administradores master têm acesso completo: não há permissões individuais para eles.</li>
 * </ul>
 */
public final class TeamGovernance {
    public static final String MASTER = "MASTER";
    public static final List<String> ROLES = List.of(MASTER, "MANAGER", "SECRETARY", "SELLER");

    private TeamGovernance() {}

    /** Quem está agindo e quem está sendo alterado. */
    public record Member(UUID accountId, String role, boolean primary) {
        public boolean master() { return MASTER.equals(role); }
    }

    /** true quando o ator pode alterar perfil, situação ou acessos do alvo. */
    public static boolean canManage(Member actor, Member target) {
        if (actor == null || target == null || !actor.master()) return false;
        if (actor.accountId().equals(target.accountId())) return false;
        if (target.primary()) return false;
        if (target.master()) return actor.primary();
        return true;
    }

    public static void requireCanManage(Member actor, Member target) {
        if (actor.accountId().equals(target.accountId()))
            throw new IllegalArgumentException("Você não pode alterar o seu próprio acesso.");
        if (target.primary())
            throw new IllegalArgumentException("O administrador principal da ótica não pode ser alterado nem removido.");
        if (target.master() && !actor.primary())
            throw new IllegalArgumentException("Somente o administrador principal gerencia outros administradores.");
        if (!canManage(actor, target))
            throw new IllegalArgumentException("Você não pode alterar o acesso desta pessoa.");
    }

    /** Perfil novo de alguém que o ator já pode gerenciar. */
    public static void requireRoleChange(Member actor, Member target, String newRole) {
        requireCanManage(actor, target);
        if (!ROLES.contains(newRole)) throw new IllegalArgumentException("Perfil inválido.");
        if (MASTER.equals(newRole) && !actor.primary())
            throw new IllegalArgumentException("Somente o administrador principal pode criar outro administrador.");
    }

    public static void requireInvite(Member actor, String role) {
        if (!ROLES.contains(role)) throw new IllegalArgumentException("Escolha um perfil válido.");
        if (MASTER.equals(role) && !actor.primary())
            throw new IllegalArgumentException("Somente o administrador principal pode convidar outro administrador.");
    }

    /** Permissões individuais existem apenas para quem não é administrador. */
    public static void requirePermissionChange(Member actor, Member target) {
        requireCanManage(actor, target);
        if (target.master())
            throw new IllegalArgumentException("Administradores master têm acesso completo; não há acessos individuais.");
    }
}
