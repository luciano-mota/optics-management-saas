package br.com.lcmtech.optics.reporting;

import java.text.Normalizer;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;

/**
 * Texto para as fontes padrão do PDF (Helvetica, codificação WinAnsi). Mantém acentos, cedilha, travessão e aspas do
 * português, que a fonte tem; troca espaços especiais (o "R$ 1.234,56" do Java usa espaço não separável) por espaço
 * comum; e só no que a fonte não tem usa a letra sem acento ou, em último caso, "?".
 */
public final class PdfText {
    private static final GlyphList GLYPHS = GlyphList.getAdobeGlyphList();

    private PdfText() {}

    public static String winAnsi(String value) {
        if (value == null) return "";
        String v = value.replace(' ', ' ').replace(' ', ' ').replace(' ', ' ')
                .replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
        StringBuilder out = new StringBuilder(v.length());
        v.codePoints().forEach(cp -> {
            if (encodable(cp)) {
                out.appendCodePoint(cp);
                return;
            }
            String plain = Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "");
            out.append(!plain.isEmpty() && plain.codePoints().allMatch(PdfText::encodable) ? plain : "?");
        });
        return out.toString();
    }

    static boolean encodable(int codePoint) {
        if (codePoint >= 0x20 && codePoint <= 0x7E) return true;
        String name = GLYPHS.codePointToName(codePoint);
        return name != null && !".notdef".equals(name) && WinAnsiEncoding.INSTANCE.contains(name);
    }
}
