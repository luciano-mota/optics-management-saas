package br.com.lcmtech.optics.publicsite;

import static org.assertj.core.api.Assertions.assertThat;
import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class SiteLogoControllerTest {
    @Test
    void logoSaturadaSugerePaletaComContraste() {
        BufferedImage logo = new BufferedImage(24,24,BufferedImage.TYPE_INT_ARGB);
        var graphics=logo.createGraphics();
        graphics.setColor(new Color(35,160,195));
        graphics.fillRect(0,0,24,24);
        graphics.dispose();
        String accent=SiteLogoController.primaryColor(logo);
        assertThat(accent).matches("#[0-9A-F]{6}").isNotEqualTo("#0D6063");
    }

    @Test
    void logoSomenteBrancaTemFallbackLegivel() {
        BufferedImage logo=new BufferedImage(10,10,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<10;y++)for(int x=0;x<10;x++)logo.setRGB(x,y,Color.WHITE.getRGB());
        assertThat(SiteLogoController.primaryColor(logo)).isEqualTo("#0D6063");
    }
}
