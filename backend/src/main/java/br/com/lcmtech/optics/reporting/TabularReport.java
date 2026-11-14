package br.com.lcmtech.optics.reporting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Relatório em tabela exportado nos três formatos com o mesmo conteúdo e os mesmos filtros:
 * CSV (separador ";" e BOM para abrir no Excel), XLSX (valores numéricos como número) e PDF (A4 deitado).
 *
 * @param money colunas que guardam valores em reais (no XLSX viram número com duas casas)
 * @param widths largura relativa de cada coluna no PDF
 */
public record TabularReport(String title, String sheetName, String fileBase, List<String> headers,
                            List<List<String>> rows, List<String> summary, boolean[] money, int[] widths) {

    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    public byte[] csv() {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(join(headers)).append("\r\n");
        for (List<String> row : rows) sb.append(join(row)).append("\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(escapeCsv(values.get(i)));
        }
        return sb.toString();
    }

    /** Aspas sempre e prefixo ' em células que começariam uma fórmula (injeção em planilhas). */
    static String escapeCsv(String input) {
        String safe = input == null ? "" : input;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    public byte[] xlsx() throws IOException {
        try (Workbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet(sheetName);
            CellStyle bold = book.createCellStyle();
            Font font = book.createFont();
            font.setBold(true);
            bold.setFont(font);
            CellStyle currency = book.createCellStyle();
            currency.setDataFormat(book.createDataFormat().getFormat("#,##0.00"));
            int r = 0;
            Row heading = sheet.createRow(r++);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = heading.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(bold);
            }
            for (List<String> values : rows) {
                Row row = sheet.createRow(r++);
                for (int i = 0; i < values.size(); i++) {
                    Cell cell = row.createCell(i);
                    String v = values.get(i);
                    if (money[i] && v != null && !v.isBlank()) {
                        cell.setCellValue(new BigDecimal(v).doubleValue());
                        cell.setCellStyle(currency);
                    } else cell.setCellValue(v == null ? "" : v);
                }
            }
            if (!summary.isEmpty()) {
                r++;
                for (String line : summary) {
                    Cell cell = sheet.createRow(r++).createCell(0);
                    cell.setCellValue(line);
                }
            }
            for (int i = 0; i < headers.size(); i++)
                sheet.setColumnWidth(i, Math.min(12000, Math.max(3000, widths[i] * 450)));
            sheet.createFreezePane(0, 1);
            book.write(out);
            return out.toByteArray();
        }
    }

    public byte[] pdf(String shop, String period) throws IOException {
        PDRectangle landscape = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
        PDType1Font boldFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        float left = 36, usable = landscape.getWidth() - 72;
        int total = 0;
        for (int w : widths) total += w;
        float[] x = new float[widths.length];
        int[] chars = new int[widths.length];
        float cursor = left;
        for (int i = 0; i < widths.length; i++) {
            x[i] = cursor;
            float width = usable * widths[i] / total;
            chars[i] = Math.max(3, (int) (width / 4.3f));
            cursor += width;
        }
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPageContentStream canvas = null;
            float y = 0;
            int page = 0;
            try {
                for (int i = -1; i < rows.size(); i++) {
                    if (canvas == null || y < 50) {
                        if (canvas != null) canvas.close();
                        PDPage p = new PDPage(landscape);
                        pdf.addPage(p);
                        canvas = new PDPageContentStream(pdf, p);
                        page++;
                        y = landscape.getHeight() - 40;
                        text(canvas, boldFont, 14, left, y, "OPTICS MANAGEMENT | " + title.toUpperCase());
                        text(canvas, font, 8, landscape.getWidth() - 90, y, "Página " + page);
                        y -= 18;
                        text(canvas, font, 9, left, y, shop + "  |  " + period);
                        y -= 18;
                        for (int c = 0; c < headers.size(); c++)
                            text(canvas, boldFont, 8, x[c], y, fit(headers.get(c), chars[c]));
                        y -= 6;
                        canvas.moveTo(left, y);
                        canvas.lineTo(left + usable, y);
                        canvas.stroke();
                        y -= 12;
                    }
                    if (i >= 0) {
                        List<String> values = rows.get(i);
                        for (int c = 0; c < values.size(); c++)
                            text(canvas, font, 8, x[c], y, fit(display(values.get(c), money[c]), chars[c]));
                        y -= 13;
                    }
                }
                if (rows.isEmpty()) {
                    text(canvas, font, 9, left, y, "Nenhum registro encontrado com os filtros escolhidos.");
                    y -= 16;
                }
                if (!summary.isEmpty()) {
                    y -= 8;
                    for (String line : summary) {
                        if (y < 40) {
                            canvas.close();
                            PDPage p = new PDPage(landscape);
                            pdf.addPage(p);
                            canvas = new PDPageContentStream(pdf, p);
                            y = landscape.getHeight() - 40;
                        }
                        text(canvas, boldFont, 9, left, y, line);
                        y -= 14;
                    }
                }
            } finally {
                if (canvas != null) canvas.close();
            }
            pdf.save(output);
            return output.toByteArray();
        }
    }

    private static String display(String value, boolean money) {
        if (!money || value == null || value.isBlank()) return value;
        return Money.brl(new BigDecimal(value));
    }

    static String ascii(String value) {
        return PdfText.winAnsi(value);
    }

    private static String fit(String value, int length) {
        String v = ascii(value);
        return v.length() > length ? v.substring(0, Math.max(1, length - 1)) + "." : v;
    }

    private static void text(PDPageContentStream canvas, PDType1Font font, int size, float x, float y, String text)
            throws IOException {
        canvas.beginText();
        canvas.setFont(font, size);
        canvas.newLineAtOffset(x, y);
        canvas.showText(ascii(text));
        canvas.endText();
    }

    /** Formatação de valores em reais para textos dos relatórios. */
    public static final class Money {
        private Money() {}

        public static String brl(BigDecimal value) {
            if (value == null) return "";
            java.text.DecimalFormat format = new java.text.DecimalFormat("#,##0.00",
                    java.text.DecimalFormatSymbols.getInstance(new java.util.Locale("pt", "BR")));
            return "R$ " + format.format(value);
        }
    }
}
