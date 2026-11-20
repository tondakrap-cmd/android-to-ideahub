package cz.ideamirror.app;

import java.io.IOException;
import java.util.regex.*;

/** Strict, bounded parser. It accepts numeric IPv4 addresses only; no DNS lookups. */
class PairCode {
    final String host,fp,secret;final int port;
    private static final Pattern FORMAT=Pattern.compile("tabulelink://([0-9.]{7,15}):([0-9]{4,5})\\?fp=([a-f0-9]{64})&key=([a-f0-9]{32})");
    PairCode(String text)throws IOException{
        if(text==null||text.length()>256)throw invalid();
        Matcher m=FORMAT.matcher(text.trim());if(!m.matches())throw invalid();
        host=m.group(1);port=Integer.parseInt(m.group(2));fp=m.group(3);secret=m.group(4);
        if(port<1024||port>65535)throw invalid();
        String[] parts=host.split("\\.",-1);if(parts.length!=4)throw invalid();
        for(String part:parts){
            if(part.isEmpty()||part.length()>3||(part.length()>1&&part.startsWith("0")))throw invalid();
            int n=Integer.parseInt(part);if(n>255)throw invalid();
        }
        int first=Integer.parseInt(parts[0]);
        if(first==0||first==127||first>=224||host.equals("255.255.255.255"))throw invalid();
    }
    private static IOException invalid(){return new IOException("Neplatný párovací kód. Načtěte QR kód z aplikace na tabuli.");}
}
