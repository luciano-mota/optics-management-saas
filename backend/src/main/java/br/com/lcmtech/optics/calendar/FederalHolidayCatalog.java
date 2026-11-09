package br.com.lcmtech.optics.calendar;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Feriados nacionais federais de referência. Manter a tabela legal versionada
 * e conferir anualmente mudanças normativas. Eventos municipais/estaduais
 * precisam de verificação independente e são armazenados na regional_holidays.
 */
public final class FederalHolidayCatalog {
    private FederalHolidayCatalog() {}

    public static Map<LocalDate,String> national(int year) {
        if(year<2000||year>2199)throw new IllegalArgumentException("Ano inválido.");
        Map<LocalDate,String> days=new LinkedHashMap<>();
        days.put(LocalDate.of(year,1,1),"Confraternização Universal");
        days.put(easter(year).minusDays(2),"Paixão de Cristo");
        days.put(LocalDate.of(year,4,21),"Tiradentes");
        days.put(LocalDate.of(year,5,1),"Dia do Trabalho");
        days.put(LocalDate.of(year,9,7),"Independência do Brasil");
        days.put(LocalDate.of(year,10,12),"Nossa Senhora Aparecida");
        days.put(LocalDate.of(year,11,2),"Finados");
        days.put(LocalDate.of(year,11,15),"Proclamação da República");
        days.put(LocalDate.of(year,11,20),"Dia Nacional de Zumbi e da Consciência Negra");
        days.put(LocalDate.of(year,12,25),"Natal");
        return Collections.unmodifiableMap(days);
    }

    /** Computus gregoriano, válido para o período de operação do SaaS. */
    public static LocalDate easter(int year) {
        if(year<2000||year>2199)throw new IllegalArgumentException("Ano inválido.");
        int a=year%19,b=year/100,c=year%100,d=b/4,e=b%4;
        int f=(b+8)/25,g=(b-f+1)/3;
        int h=(19*a+b-d-g+15)%30;
        int i=c/4,k=c%4;
        int l=(32+2*e+2*i-h-k+7*30)%7;
        int m=(a+11*h+22*l)/451;
        int month=(h+l-7*m+114)/31;
        int day=(h+l-7*m+114)%31+1;
        return LocalDate.of(year,month,day);
    }
}
