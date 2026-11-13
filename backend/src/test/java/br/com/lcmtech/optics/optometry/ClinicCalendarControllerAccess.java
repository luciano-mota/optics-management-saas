package br.com.lcmtech.optics.optometry;

/** Acesso de teste à dobra de linhas do iCal. */
public final class ClinicCalendarControllerAccess {
    private ClinicCalendarControllerAccess() {}

    public static void line(StringBuilder out, String content) {
        ClinicCalendarController.line(out, content);
    }
}
