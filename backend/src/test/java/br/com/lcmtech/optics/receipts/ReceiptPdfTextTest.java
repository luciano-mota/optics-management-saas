package br.com.lcmtech.optics.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lcmtech.optics.reporting.PdfText;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** O recibo vai para o cliente: razão social, valores e datas precisam sair exatamente como no Brasil. */
class ReceiptPdfTextTest {

    private static String text(byte[] pdf) throws Exception {
        try (var doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void reciboMantemAcentosValorEmReaisEDataBrasileira() throws Exception {
        var d = new ReceiptPdfRenderer.Details(UUID.randomUUID(), 7, "Ótica São João", "Ótica São João LTDA",
                "11222333000181", "Rua Grande, 100 - Centro, São Luís/MA", "(98) 3222-0000", "Conceição Araújo",
                "39053344705", "Essilor Varilux", "Ray-Ban", "PIX", LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4),
                new BigDecimal("1234.56"), BigDecimal.ZERO, new BigDecimal("1234.56"), BigDecimal.ZERO,
                "Dona Inês", "Sócia", null, null, LocalDate.of(2027, 11, 3));
        String t = text(ReceiptPdfRenderer.render(d));
        assertThat(t).contains("Emitente: Ótica São João LTDA", "Endereço: Rua Grande, 100 - Centro, São Luís/MA",
                "Cliente: Conceição Araújo", "Valor pago pelo cliente: R$ 1.234,56", "Pagamento: 04/11/2026",
                "Data da venda: 03/11/2026", "Garantia até: 03/11/2027", "Forma: Pix", "IDENTIFICAÇÃO", "Sócia",
                "Não substitui nota fiscal");
        assertThat(t).doesNotContain("R$?", "Otica", "2026-11-04", "39053344705");
    }

    @Test
    void textoForaDaFonteViraLetraSemAcentoOuInterrogacaoSemQuebrarOPdf() {
        assertThat(PdfText.winAnsi("R$ 1.234,56")).isEqualTo("R$ 1.234,56");
        assertThat(PdfText.winAnsi("Ótica – “Visão” ção")).isEqualTo("Ótica – “Visão” ção");
        assertThat(PdfText.winAnsi("Ő")).isEqualTo("O"); // fora do WinAnsi: letra sem acento
        assertThat(PdfText.winAnsi("😀")).isEqualTo("?");
        assertThat(PdfText.winAnsi(null)).isEmpty();
    }
}
