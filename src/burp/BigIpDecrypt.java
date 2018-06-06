/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package burp;

import extend.util.Util;
import java.nio.ByteOrder;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author isayan
 */
public class BigIpDecrypt {

    private String ipAddr = "";
    private String encryptCookie = "";
    private int startPos = -1;
    private int endPos = -1;
    private boolean startsBIGipServer = false;
    private final static Pattern REQUEST_COOKE = Pattern.compile("^Cookie: (.*)$", Pattern.MULTILINE);
    private final static Pattern RESPONSE_COOKE = Pattern.compile("^Set-Cookie: (.*)$", Pattern.MULTILINE);

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        try {
            String encrypt_value = null;
            for (String arg : args) {
                String[] param = arg.split("=", 2);
                if (param.length < 2) {
                    throw new IllegalArgumentException("argment err:" + String.join(" ", param));
                }
                if ("-d".equals(param[0])) {
                    encrypt_value = param[1];
                }
            }

            // 必須チェック
            if (encrypt_value == null) {
                System.out.println("-d argument err ");
                usage();
                return;
            }

            String bigIPaddr = BigIpDecrypt.decrypt(encrypt_value);
            System.out.println(bigIPaddr);
            System.out.println("PrivateIP:" + IpUtil.isPrivateIP(bigIPaddr));

        } catch (Exception ex) {
            String errmsg = String.format("%s: %s", ex.getClass().getName(), ex.getMessage());
            System.out.println(errmsg);
            Logger.getLogger(BigIpDecrypt.class.getName()).log(Level.SEVERE, null, ex);
            usage();
        }
    }

    private static void usage() {
        System.out.println(String.format("Usage: java -jar %s.jar -d=<encrypt>", BigIpDecrypt.class.getSimpleName()));
        System.out.println("");
    }

    /**
     * https://www.owasp.org/index.php/SCG_D_BIGIP
     * BIGipServer<pool_name>=1677787402.36895.0000
     * BIGipServer<pool_name>=vi20010112000000000000000000000030.20480
     * BIGipServer<pool_name>=rd5o00000000000000000000ffffc0000201o80
     * BIGipServer<pool_name>=rd3o20010112000000000000000000000030o80
    **
     */
    private final static String IPv4_PREFIX = "00000000000000000000ffff";
    private final static Pattern BIGIP_COOKIE = Pattern.compile("(BIGipServer[^\\s=]*?|[^\\s=]*?)=([0-9a-z.]+)");
    private final static Pattern BIGIP_STANDARD = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
    private final static Pattern BIGIP_STANDARD_VI = Pattern.compile("vi(\\d+)\\.(\\d+)");
    private final static Pattern BIGIP_STANDARD_RD = Pattern.compile("rd\\d+o([0-9a-z]+)o(\\d+)");

    private BigIpDecrypt() {
    }

    public static BigIpDecrypt parseDecrypt(boolean messageIsRequest, byte[] messageByte) {
        BigIpDecrypt decryptList[] = parseDecrypts(messageIsRequest, messageByte);
        if (decryptList.length > 0) {
            return decryptList[0];
        } else {
            return null;
        }
    }

    public static BigIpDecrypt[] parseDecrypts(boolean messageIsRequest, byte[] messageByte) {
        List<BigIpDecrypt> decryptList = new ArrayList<>();
        String message = Util.getRawStr(messageByte);
        String cookieAll = null;
        int cookieOffset = 0;

        if (messageIsRequest) {
            // Cookieの取得
            Matcher m = REQUEST_COOKE.matcher(message);
            while (m.find()) {
                cookieOffset = m.start(1);
                cookieAll = m.group(1);
                decryptList.addAll(parseDecryptList(cookieAll, cookieOffset));
            }
        } else {
            BigIpDecrypt bigIP = new BigIpDecrypt();
            // Set-Cookieの取得
            Matcher m = RESPONSE_COOKE.matcher(message);
            while (m.find()) {
                cookieOffset = m.start(1);
                cookieAll = m.group(1);
                decryptList.addAll(parseDecryptList(cookieAll, cookieOffset));
            }
        }
        return decryptList.toArray(new BigIpDecrypt[0]);
    }

    private static List<BigIpDecrypt> parseDecryptList(String cookieAll, int cookieOffset) {
        List<BigIpDecrypt> decryptList = new ArrayList<>();
        if (cookieAll != null) {
            Matcher m = BIGIP_COOKIE.matcher(cookieAll);
            while (m.find()) {
                BigIpDecrypt bigIP = new BigIpDecrypt();
                String cookieName = m.group(1);
                String cookieValue = m.group(2);
                String ip_addr = decrypt(cookieValue);
                if (ip_addr != null) {
                    bigIP.startsBIGipServer = cookieName.startsWith("BIGipServer");
                    bigIP.ipAddr = ip_addr;
                    bigIP.encryptCookie = cookieValue;
                    bigIP.startPos = cookieOffset + m.start();
                    bigIP.endPos = cookieOffset + m.end();
                    decryptList.add(bigIP);
                }
            }
        }
        return decryptList;
    }

    /*
     * https://support.f5.com/csp/article/K6917
    **/
    protected static String decrypt(String value) {
        String ipaddr = null;
        if (ipaddr == null) {
            Matcher m1 = BIGIP_STANDARD.matcher(value);
            if (m1.find()) {
                String enc_ip = m1.group(1);
                String enc_port = m1.group(2);
                ipaddr = String.format("%s:%d", IpUtil.decimalToIPv4(Long.parseLong(enc_ip), ByteOrder.LITTLE_ENDIAN), IpUtil.decimalToPort(Integer.parseInt(enc_port), ByteOrder.LITTLE_ENDIAN));
            }
        }
        if (ipaddr == null) {
            Matcher m1 = BIGIP_STANDARD_VI.matcher(value);
            if (m1.find()) {
                String enc_ip = m1.group(1);
                String enc_port = m1.group(2);
                ipaddr = String.format("%s:%d", IpUtil.hexToIPv6(enc_ip), IpUtil.decimalToPort(Integer.parseInt(enc_port), ByteOrder.LITTLE_ENDIAN));
            }
        }
        if (ipaddr == null) {
            Matcher m1 = BIGIP_STANDARD_RD.matcher(value);
            if (m1.find()) {
                String enc_ip = m1.group(1);
                String enc_port = m1.group(2);
                // ::ffff
                if (enc_ip.startsWith(IPv4_PREFIX)) {
                    ipaddr = String.format("%s:%d", IpUtil.hexToIPv4(enc_ip.substring(IPv4_PREFIX.length()), ByteOrder.BIG_ENDIAN), IpUtil.decimalToPort(Integer.parseInt(enc_port), ByteOrder.BIG_ENDIAN));
                } else {
                    ipaddr = String.format("%s:%d", IpUtil.hexToIPv6(enc_ip), IpUtil.decimalToPort(Integer.parseInt(enc_port), ByteOrder.BIG_ENDIAN));
                }
            }
        }
        // 
        return ipaddr;
    }

    public boolean isPrivateIP() {
        try {
            return IpUtil.isPrivateIP(this.ipAddr);
        } catch (ParseException ex) {
            return false;
        }
    }

    public boolean startsBIGipServer() {
        return this.startsBIGipServer;
    }

    public String getEncryptCookie() {
        return this.encryptCookie;
    }

    public String getIPAddr() {
        return this.ipAddr;
    }

    public int start() {
        return this.startPos;
    }

    public int end() {
        return this.endPos;
    }

}