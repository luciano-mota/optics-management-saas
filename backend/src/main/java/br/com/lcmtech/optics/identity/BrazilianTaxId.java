package br.com.lcmtech.optics.identity;

import java.util.Locale;

/** CNPJ com 12 posições alfanuméricas + 2 verificadores numéricos (Receita Federal 2026). */
public final class BrazilianTaxId {
    private BrazilianTaxId() {}
    public static String normalizeCnpj(String raw) {
        return raw==null?"":raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");
    }
    public static boolean validCnpj(String normalized) {
        if(normalized==null || !normalized.matches("[A-Z0-9]{12}[0-9]{2}") ||
                normalized.chars().distinct().count()==1) return false;
        int[] w1={5,4,3,2,9,8,7,6,5,4,3,2};
        int[] w2={6,5,4,3,2,9,8,7,6,5,4,3,2};
        int sum=0;
        for(int i=0;i<12;i++)sum+=(normalized.charAt(i)-48)*w1[i];
        int first=sum%11<2?0:11-sum%11;
        if(first!=normalized.charAt(12)-48)return false;
        sum=0;
        for(int i=0;i<13;i++)sum+=(normalized.charAt(i)-48)*w2[i];
        return (sum%11<2?0:11-sum%11)==normalized.charAt(13)-48;
    }
}
