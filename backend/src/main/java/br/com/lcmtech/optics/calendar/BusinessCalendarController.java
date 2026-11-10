package br.com.lcmtech.optics.calendar;

import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Calendário operacional por ótica. Uma data aberta conta como um dia de
 * funcionamento, mesmo sem vendas. Feriados locais não verificados nunca
 * são inventados; avisos de cobertura ficam visíveis para o Master.
 */
@RestController
@RequestMapping("/api/v1/business-calendar")
public class BusinessCalendarController {
    private final JdbcTemplate jdbc;
    private final OpticContext access;

    private static final String FEDERAL_SOURCE =
        "https://www.planalto.gov.br/ccivil_03/leis/l9093.htm";

    public BusinessCalendarController(JdbcTemplate jdbc, OpticContext access) {
        this.jdbc = jdbc; this.access = access;
    }

    public record Holiday(String name, String scope, String source, String sourceUrl, boolean verified) {}
    public record CalendarDay(LocalDate date, int weekday, boolean normallyOpen,
            boolean willOpen, boolean overridden, List<Holiday> holidays) {}
    public record MonthPlan(int year,int month,String timeZone,String stateUf,String municipalityIbge,
            boolean reviewed, boolean localHolidaysVerified,
            List<String> warnings, int totalOpenDays, int openDaysThroughToday,
            int remainingOpenDays, List<CalendarDay> days) {}

    public record Settings(String stateUf, String municipalityIbge,
            String municipalityName, String timeZone, List<Integer> openWeekdays) {}

    @GetMapping("/settings")
    public Map<String,Object> settings(Authentication auth,HttpSession session) {
        access.require(auth,session,"REPORTS_READ");
        UUID optic=access.optic(auth,session);
        List<Map<String,Object>> items=jdbc.queryForList("SELECT state_uf,municipality_ibge,"
                +"municipality_name,time_zone,open_weekdays FROM optic_business_calendar WHERE optic_id=?",optic);
        if(items.isEmpty()) return Map.of("timeZone","America/Fortaleza",
                "openWeekdays",List.of(1,2,3,4,5,6),"configured",false);
        Map<String,Object> result=new HashMap<>(items.get(0));
        result.put("configured",result.get("state_uf")!=null && result.get("municipality_ibge")!=null);
        return result;
    }

    @PutMapping("/settings")
    @Transactional
    public Map<String,Boolean> updateSettings(@RequestBody Settings req, Authentication auth,HttpSession session) {
        access.requireMaster(auth,session);
        if(req==null||req.stateUf()==null||!req.stateUf().matches("[A-Z]{2}")||
                req.municipalityIbge()==null||!req.municipalityIbge().matches("[0-9]{7}")||
                req.municipalityName()==null||req.municipalityName().isBlank()||
                req.timeZone()==null||req.openWeekdays()==null||req.openWeekdays().isEmpty())
            throw new IllegalArgumentException("Informe UF, cidade, código IBGE, fuso e dias de atendimento.");
        try { ZoneId.of(req.timeZone()); } catch(Exception e) {
            throw new IllegalArgumentException("Fuso horário inválido.");
        }
        Set<Integer> weekdays=new HashSet<>(req.openWeekdays());
        if(weekdays.size()!=req.openWeekdays().size()||
                weekdays.stream().anyMatch(day->day<1||day>7))
            throw new IllegalArgumentException("Informe dias da semana entre 1 (segunda) e 7 (domingo).");
        String serialized=weekdays.stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        UUID optic=access.optic(auth,session),actor=access.accountId(auth);
        jdbc.update("""
            INSERT INTO optic_business_calendar
                (optic_id,state_uf,municipality_ibge,municipality_name,time_zone,
                 open_weekdays,updated_by)
            VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(optic_id) DO UPDATE SET
                state_uf=excluded.state_uf,municipality_ibge=excluded.municipality_ibge,
                municipality_name=excluded.municipality_name,time_zone=excluded.time_zone,
                open_weekdays=excluded.open_weekdays,updated_by=excluded.updated_by,
                updated_at=now()
            """,optic,req.stateUf(),req.municipalityIbge(),req.municipalityName(),
                req.timeZone(),serialized,actor);
        audit(optic,actor,"CALENDAR_SETTINGS_UPDATED");
        return Map.of("saved",true);
    }

    @GetMapping("/month")
    public MonthPlan month(@RequestParam(required=false) Integer year,
               @RequestParam(required=false) Integer month,
               Authentication auth,HttpSession session) {
        access.require(auth,session,"REPORTS_READ");
        UUID optic=access.optic(auth,session);
        return buildMonth(optic,year,month);
    }

    /** Permite fechamento do relatório na data selecionada sem usar o relógio atual. */
    public MonthPlan forReporting(UUID optic,int year,int month,LocalDate cutoff) {
        return buildMonth(optic,year,month,cutoff);
    }

    private MonthPlan buildMonth(UUID optic,Integer yearParam,Integer monthParam) {
        return buildMonth(optic,yearParam,monthParam,null);
    }

    private MonthPlan buildMonth(UUID optic,Integer yearParam,Integer monthParam,LocalDate cutoff) {
        Map<String,Object> setup=jdbc.queryForList(
             "SELECT state_uf,municipality_ibge,time_zone,open_weekdays "
                +"FROM optic_business_calendar WHERE optic_id=?",optic)
            .stream().findFirst().orElse(Map.of("time_zone","America/Fortaleza",
                                            "open_weekdays","1,2,3,4,5,6"));
        String tz=(String)setup.get("time_zone");
        ZoneId zone=ZoneId.of(tz);
        LocalDate today=cutoff==null?LocalDate.now(zone):cutoff;
        int year=yearParam==null?today.getYear():yearParam;
        int month=monthParam==null?today.getMonthValue():monthParam;
        if(year<2000||year>2199||month<1||month>12)
            throw new IllegalArgumentException("Mês ou ano inválido.");
        YearMonth period=YearMonth.of(year,month);
        String uf=(String)setup.get("state_uf");
        String city=(String)setup.get("municipality_ibge");
        Set<Integer> weekdays=new HashSet<>();
        for(String part:((String)setup.get("open_weekdays")).split(",")) {
            try {weekdays.add(Integer.parseInt(part));}
            catch(NumberFormatException e){throw new IllegalArgumentException("Calendário semanal inválido.");}
        }
        // Calendário nacional estático de referência; fonte regional exige sync validado.
        Map<LocalDate,List<Holiday>> known=new HashMap<>();
        for(var entry:FederalHolidayCatalog.national(year).entrySet())
            known.computeIfAbsent(entry.getKey(), key->new ArrayList<>())
                    .add(new Holiday(entry.getValue(),"NATIONAL","Lei federal",FEDERAL_SOURCE,true));
        String query="""
            SELECT holiday_date,name,scope,source_name,source_url,verified_at
            FROM regional_holidays
            WHERE calendar_year=?
              AND (scope='NATIONAL'
                  OR (scope='STATE' AND state_uf=?)
                  OR (scope='MUNICIPAL' AND municipality_ibge=?)
                  OR (scope='OPTIONAL' AND (state_uf IS NULL OR state_uf=?)
                      AND (municipality_ibge IS NULL OR municipality_ibge=?)))
            ORDER BY holiday_date,scope
            """;
        for(var row:jdbc.queryForList(query,year,uf,city,uf,city)){
            LocalDate day=((java.sql.Date)row.get("holiday_date")).toLocalDate();
            Holiday holiday=new Holiday((String)row.get("name"),(String)row.get("scope"),
                (String)row.get("source_name"),(String)row.get("source_url"),
                row.get("source_url")!=null && row.get("verified_at")!=null);
            List<Holiday> list=known.computeIfAbsent(day,key->new ArrayList<>());
            if(list.stream().noneMatch(h->h.name().equalsIgnoreCase(holiday.name())
                    && h.scope().equals(holiday.scope()))) list.add(holiday);
        }
        Map<LocalDate,Boolean> overrides=new HashMap<>();
        for(var row:jdbc.queryForList(
                "SELECT day,is_open FROM optic_day_overrides WHERE optic_id=? AND day BETWEEN ? AND ?",
                optic,java.sql.Date.valueOf(period.atDay(1)),
                java.sql.Date.valueOf(period.atEndOfMonth()))) {
            overrides.put(((java.sql.Date)row.get("day")).toLocalDate(),
                    (Boolean)row.get("is_open"));
        }
        Integer reviewed=jdbc.queryForObject(
                "SELECT count(*) FROM optic_month_reviews WHERE optic_id=? AND calendar_year=? AND calendar_month=?",
                Integer.class,optic,year,month);
        List<String> warnings=new ArrayList<>();
        if(uf==null||city==null) warnings.add("Configure UF e município IBGE para verificar feriados locais.");
        int syncCount=0;
        if(uf!=null&&city!=null) {
            Integer count=jdbc.queryForObject("""
                SELECT count(*) FROM regional_holiday_sync
                WHERE calendar_year=? AND success=true AND region_key IN (?,?)
                """,Integer.class,year,"UF:"+uf,"MUN:"+city);
            syncCount=count==null?0:count;
            if(syncCount<2) warnings.add("Feriados estaduais ou municipais não foram totalmente verificados. Confira manualmente.");
        }
        boolean localVerified=uf!=null&&city!=null&&syncCount==2;
        List<CalendarDay> days=new ArrayList<>();
        int total=0,elapsed=0,remaining=0;
        for(int index=1;index<=period.lengthOfMonth();index++) {
            LocalDate date=period.atDay(index);
            boolean usual=weekdays.contains(date.getDayOfWeek().getValue());
            List<Holiday> holidays=List.copyOf(known.getOrDefault(date,List.of()));
            boolean legal=holidays.stream().anyMatch(h->
                !h.scope().equals("OPTIONAL") && h.verified());
            boolean open=overrides.getOrDefault(date,usual&&!legal);
            if(open) {
                total++;
                if(!date.isAfter(today))elapsed++;
                else remaining++;
            }
            days.add(new CalendarDay(date,date.getDayOfWeek().getValue(),usual,open,
                    overrides.containsKey(date),holidays));
        }
        return new MonthPlan(year,month,tz,uf,city,reviewed!=null&&reviewed>0,
            localVerified,List.copyOf(warnings),total,elapsed,remaining,List.copyOf(days));
    }

    public record DayChange(boolean willOpen,String title,String sourceUrl) {}

    @PutMapping("/days/{date}")
    @Transactional
    public Map<String,Boolean> changeDay(@PathVariable LocalDate date,
            @RequestBody DayChange request,Authentication auth,HttpSession session) {
        access.requireMaster(auth,session);
        if(request==null||date==null||date.getYear()<2000||date.getYear()>2199)
            throw new IllegalArgumentException("Data ou expediente inválido.");
        UUID optic=access.optic(auth,session),actor=access.accountId(auth);
        jdbc.update("""
                INSERT INTO optic_day_overrides(optic_id,day,is_open,title,source_url,updated_by)
                VALUES(?,?,?,?,?,?)
                ON CONFLICT(optic_id,day) DO UPDATE SET
                    is_open=excluded.is_open,title=excluded.title,
                    source_url=excluded.source_url,updated_by=excluded.updated_by,
                    updated_at=now()
                """,optic,java.sql.Date.valueOf(date),request.willOpen(),request.title(),
                    request.sourceUrl(),actor);
        audit(optic,actor,"CALENDAR_DAY_UPDATED");
        return Map.of("saved",true);
    }

    public record Review(int year,int month,Map<LocalDate,Boolean> decisions) {}

    @PostMapping("/review")
    @Transactional
    public Map<String,Object> review(@RequestBody Review request,
            Authentication auth,HttpSession session) {
        access.requireMaster(auth,session);
        if(request==null||request.year()<2000||request.year()>2199||
                request.month()<1||request.month()>12||request.decisions()==null)
            throw new IllegalArgumentException("Informe mês e escolhas de expediente.");
        YearMonth period=YearMonth.of(request.year(),request.month());
        if(request.decisions().size()>31 || request.decisions().keySet().stream()
                .anyMatch(day->!YearMonth.from(day).equals(period)))
            throw new IllegalArgumentException("Data fora do mês selecionado.");
        UUID optic=access.optic(auth,session),actor=access.accountId(auth);
        for(var entry:request.decisions().entrySet()) {
            jdbc.update("""
                INSERT INTO optic_day_overrides(optic_id,day,is_open,title,updated_by)
                VALUES(?,?,?,'Decisão de expediente na revisão mensal',?)
                ON CONFLICT(optic_id,day) DO UPDATE SET
                    is_open=excluded.is_open,updated_by=excluded.updated_by,updated_at=now()
                """,optic,java.sql.Date.valueOf(entry.getKey()),entry.getValue(),actor);
        }
        jdbc.update("""
            INSERT INTO optic_month_reviews(optic_id,calendar_year,calendar_month,reviewed_by)
            VALUES(?,?,?,?)
            ON CONFLICT(optic_id,calendar_year,calendar_month) DO UPDATE SET
                reviewed_by=excluded.reviewed_by,reviewed_at=now()
            """,optic,request.year(),request.month(),actor);
        audit(optic,actor,"CALENDAR_MONTH_REVIEWED");
        MonthPlan plan=buildMonth(optic,request.year(),request.month());
        return Map.of("reviewed",true,"openDays",plan.totalOpenDays(),
                "localHolidaysVerified",plan.localHolidaysVerified());
    }

    private void audit(UUID optic,UUID account,String name) {
        jdbc.update("INSERT INTO audit_events(id,optic_id,actor_account_id,event_type) VALUES(?,?,?,?)",
                UUID.randomUUID(),optic,account,name);
    }
}
