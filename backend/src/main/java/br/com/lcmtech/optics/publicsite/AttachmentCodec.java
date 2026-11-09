package br.com.lcmtech.optics.publicsite;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Validação de tamanho, formato e assinatura do anexo antes do banco. */
public final class AttachmentCodec {
    private AttachmentCodec() {}
    public static void store(JdbcTemplate jdbc,UUID optic,UUID customer,UUID sale,UUID visit,
                             String filename,String mime,String encoded){
        if(mime==null||!List.of("application/pdf","image/jpeg","image/png","image/webp").contains(mime))
            throw new IllegalArgumentException("Envie PDF, JPG, PNG ou WebP.");
        if(encoded==null||encoded.length()>5_700_000)
            throw new IllegalArgumentException("O arquivo ultrapassou 4 MB.");
        byte[] content;
        try {content=Base64.getDecoder().decode(encoded);}
        catch(IllegalArgumentException e){throw new IllegalArgumentException("Arquivo inválido.");}
        if(content.length==0||content.length>4*1024*1024)
            throw new IllegalArgumentException("O arquivo deve ter até 4 MB.");
        boolean ok=switch(mime){
            case "application/pdf" -> starts(content,new int[]{0x25,0x50,0x44,0x46});
            case "image/png" -> starts(content,new int[]{0x89,0x50,0x4E,0x47});
            case "image/jpeg" -> starts(content,new int[]{0xFF,0xD8,0xFF});
            case "image/webp" -> starts(content,new int[]{0x52,0x49,0x46,0x46}) &&
                content.length>12 && content[8]=='W' && content[9]=='E' &&
                content[10]=='B' && content[11]=='P';
            default -> false;
        };
        if(!ok)throw new IllegalArgumentException("O conteúdo não corresponde ao tipo informado.");
        String safeName=filename==null?"receita":filename.replaceAll("[^a-zA-Z0-9._-]","_");
        jdbc.update("INSERT INTO file_attachments(optic_id,id,customer_id,sale_id,visit_id,"
            +"original_name,content_type,bytes) VALUES(?,?,?,?,?,?,?,?)",
            optic,UUID.randomUUID(),customer,sale,visit,safeName,mime,content);
    }
    private static boolean starts(byte[] content,int[] sig){
        if(content.length<sig.length)return false;
        for(int i=0;i<sig.length;i++)if((content[i]&255)!=sig[i])return false;
        return true;
    }
}
