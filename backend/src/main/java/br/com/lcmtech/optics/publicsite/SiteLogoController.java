package br.com.lcmtech.optics.publicsite;

import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Logo opcional para as óticas com site autorizado. Extrai uma cor dominante
 * com contraste adequado para ser aplicada como paleta sugerida da página.
 */
@RestController
public class SiteLogoController {
    private final JdbcTemplate jdbc;
    private final OpticContext access;
    public SiteLogoController(JdbcTemplate jdbc, OpticContext access) {
        this.jdbc = jdbc; this.access = access;
    }

    public record LogoInput(String mime,String dataBase64) {}

    @PostMapping("/api/v1/site-settings/logo")
    public Map<String,String> logo(
            @RequestBody LogoInput input,Authentication auth,HttpSession session) {
        access.require(auth, session, "SITE_MANAGE");
        UUID optic = access.optic(auth, session);
        Integer enabled=jdbc.queryForObject(
                "SELECT count(*) FROM optics WHERE id=? AND site_enabled",
                Integer.class, optic);
        if(enabled==null||enabled!=1) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Este site ainda não foi liberado pela plataforma.");
        }
        if(input==null||input.mime()==null||
                !(input.mime().equals("image/png")||input.mime().equals("image/jpeg"))||
                input.dataBase64()==null||input.dataBase64().length()>2_800_000) {
            throw new IllegalArgumentException("Envie uma imagem PNG ou JPG de até 2 MB.");
        }
        byte[] bytes;
        try {bytes=Base64.getDecoder().decode(input.dataBase64());}
        catch(IllegalArgumentException e) {
            throw new IllegalArgumentException("Imagem inválida.");
        }
        if(bytes.length==0||bytes.length>2*1024*1024) {
            throw new IllegalArgumentException("O arquivo deve ter até 2 MB.");
        }
        try {
            BufferedImage image=ImageIO.read(new ByteArrayInputStream(bytes));
            if(image==null||image.getWidth()>4096||image.getHeight()>4096) {
                throw new IllegalArgumentException("A imagem tem tamanho ou formato inválido.");
            }
            String primary=primaryColor(image);
            String secondary=lighten(primary);
            jdbc.update("""
                    UPDATE site_settings SET logo=?,logo_content_type=?,
                    primary_color=?,secondary_color=?,updated_at=now() WHERE optic_id=?
                    """,bytes,input.mime(),primary,secondary,optic);
            return Map.of("primaryColor",primary,"secondaryColor",secondary);
        } catch(IOException e) {
            throw new IllegalArgumentException("Não foi possível abrir a imagem.");
        }
    }

    @GetMapping("/api/v1/public/site/{slug}/logo")
    public ResponseEntity<byte[]> publicLogo(@PathVariable String slug) {
        Map<String,Object> saved=jdbc.queryForList("""
                SELECT s.logo,s.logo_content_type FROM optics o
                JOIN site_settings s ON s.optic_id=o.id
                WHERE o.slug=? AND o.site_enabled AND o.active AND s.logo IS NOT NULL
                """,slug).stream().findFirst()
                .orElseThrow(()->new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)))
                .header("X-Content-Type-Options","nosniff")
                .contentType(MediaType.parseMediaType((String)saved.get("logo_content_type")))
                .body((byte[])saved.get("logo"));
    }

    static String primaryColor(BufferedImage image) {
        long red=0,green=0,blue=0,total=0;
        int stepx=Math.max(1,image.getWidth()/120);
        int stepy=Math.max(1,image.getHeight()/120);
        for(int y=0;y<image.getHeight();y+=stepy) {
            for(int x=0;x<image.getWidth();x+=stepx) {
                Color c=new Color(image.getRGB(x,y),true);
                if(c.getAlpha()<180)continue;
                int high=Math.max(c.getRed(),Math.max(c.getGreen(),c.getBlue()));
                int low=Math.min(c.getRed(),Math.min(c.getGreen(),c.getBlue()));
                if(high>245&&low>235||high<35)continue;
                int saturation=high-low;
                if(saturation<18)continue;
                int weight=1+saturation/30;
                red+=(long)c.getRed()*weight;
                green+=(long)c.getGreen()*weight;
                blue+=(long)c.getBlue()*weight;
                total+=weight;
            }
        }
        if(total==0)return "#0D6063";
        int r=(int)(red/total),g=(int)(green/total),b=(int)(blue/total);
        // A página usa texto branco no fundo primário: escurecer se necessário.
        for(int i=0;i<10 && contrastWithWhite(r,g,b)<4.6;i++) {
            r=(int)(r*0.85);g=(int)(g*0.85);b=(int)(b*0.85);
        }
        return String.format("#%02X%02X%02X",r,g,b);
    }

    private static String lighten(String hex) {
        int r=Integer.parseInt(hex.substring(1,3),16);
        int g=Integer.parseInt(hex.substring(3,5),16);
        int b=Integer.parseInt(hex.substring(5,7),16);
        return String.format("#%02X%02X%02X",
                (int)(r*0.10+255*0.90),
                (int)(g*0.10+255*0.90),
                (int)(b*0.10+255*0.90));
    }

    private static double contrastWithWhite(int r,int g,int b) {
        double lum=0.2126*channel(r)+0.7152*channel(g)+0.0722*channel(b);
        return 1.05/(lum+0.05);
    }
    private static double channel(int component) {
        double c=component/255.0;
        return c<=0.04045?c/12.92:Math.pow((c+0.055)/1.055,2.4);
    }
}
