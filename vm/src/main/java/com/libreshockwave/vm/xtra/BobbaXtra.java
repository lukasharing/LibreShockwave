package com.libreshockwave.vm.xtra;

import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.DebugConfig;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * BobbaXtra compatibility for encrypted MUS login flows.
 */
public final class BobbaXtra implements Xtra {

    private static final boolean TRACE_CRYPTO = false;
    private static final BigInteger P = new BigInteger(
        "632158881801130885249042417232212770524741295422564233061391190031954228421232913648184592218883487397503624904102572293826728806813079");
    private static final BigInteger G = new BigInteger("23786635532332886537261431906453031264918297");
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final int DH_KEY_LENGTH = 56;
    private static final byte[] HKDF_SALT = ascii("BobbaXtraHKDFSalt");
    private static final byte[] INFO_PREFIX = ascii("BobbaXtra|");
    private static final char[] CIPHER_TEXT_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final char[] MACHINE_ID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final String MACHINE_SEED_PROPERTY = "libreshockwave.bobba.machineSeed";
    private static final Map<String, String> PUBLIC_HANDLERS = publicHandlers();
    private static final String PROCESS_SEED = "process:"
            + Long.toHexString(System.nanoTime())
            + ":"
            + Long.toHexString(new Random(System.nanoTime()).nextLong());
    private static String runtimeMachineSeed = "";
    private static String cachedMachineId = "";

    private final Map<Integer, InstanceState> instances = new HashMap<>();
    private final Random random = new Random(System.nanoTime());
    private int nextInstanceId = 1;

    @Override
    public String getName() {
        return "BobbaXtra";
    }

    @Override
    public int createInstance(List<Datum> args) {
        int id = nextInstanceId++;
        instances.put(id, new InstanceState());
        return id;
    }

    @Override
    public void destroyInstance(int instanceId) {
        instances.remove(instanceId);
    }

    @Override
    public Datum callHandler(int instanceId, String handlerName, List<Datum> args) {
        InstanceState state = instances.get(instanceId);
        if (state == null) {
            return Datum.VOID;
        }

        try {
            String handler = canonicalHandlerLookupName(handlerName);
            if (handler == null) {
                return unsupported(state, handlerName);
            }
            return switch (handler) {
                case "cryptoreset" -> {
                    state.reset();
                    yield Datum.TRUE;
                }
                case "devicegetmachineid" -> Datum.of(machineId());
                case "cryptogeneratepublickey" -> Datum.of(generatePublicKey(state));
                case "cryptosetserverpublickey" -> setServerPublicKey(state, args);
                case "cryptoisready" -> state.ready ? Datum.TRUE : Datum.ZERO;
                case "cryptoencryptpayload" -> encrypt(state, args, state.clientToServerData, "c2s payload");
                case "cryptoencryptheader" -> encrypt(state, args, state.clientToServerHeader, "c2s header");
                case "cryptodecryptpayload" -> decrypt(state, args, state.serverToClientData, "s2c payload");
                case "cryptodecryptheader" -> decrypt(state, args, state.serverToClientHeader, "s2c header");
                case "cryptogetsharedkeyhex" -> Datum.of(bytesToHex(state.sharedKeyBytes));
                case "cryptogetlasterror" -> Datum.of(state.lastError);
                default -> unsupported(state, handlerName);
            };
        } catch (RuntimeException e) {
            state.lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            System.err.println("[BobbaXtra] " + handlerName + " failed: " + state.lastError);
            return Datum.VOID;
        }
    }

    @Override
    public String resolveHandlerName(int instanceId, String handlerName, List<String> candidateNames) {
        String direct = canonicalPublicHandlerName(handlerName);
        if (direct != null) {
            return direct;
        }
        if (candidateNames != null) {
            for (String candidate : candidateNames) {
                String resolved = canonicalPublicHandlerName(candidate);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return handlerName;
    }

    private static Map<String, String> publicHandlers() {
        Map<String, String> handlers = new HashMap<>();
        registerPublicHandler(handlers, "Crypto_Reset");
        registerPublicHandler(handlers, "Crypto_GeneratePublicKey");
        registerPublicHandler(handlers, "Crypto_SetServerPublicKey");
        registerPublicHandler(handlers, "Crypto_IsReady");
        registerPublicHandler(handlers, "Crypto_EncryptPayload");
        registerPublicHandler(handlers, "Crypto_DecryptPayload");
        registerPublicHandler(handlers, "Crypto_EncryptHeader");
        registerPublicHandler(handlers, "Crypto_DecryptHeader");
        registerPublicHandler(handlers, "Crypto_GetSharedKeyHex");
        registerPublicHandler(handlers, "Crypto_GetLastError");
        registerPublicHandler(handlers, "Device_GetMachineId");
        return Map.copyOf(handlers);
    }

    private static void registerPublicHandler(Map<String, String> handlers, String handlerName) {
        handlers.put(normalizeHandlerName(handlerName), handlerName);
    }

    private static String canonicalPublicHandlerName(String handlerName) {
        return PUBLIC_HANDLERS.get(normalizeHandlerName(handlerName));
    }

    private static String canonicalHandlerLookupName(String handlerName) {
        String canonical = canonicalPublicHandlerName(handlerName);
        return canonical != null ? normalizeHandlerName(canonical) : null;
    }

    private static String normalizeHandlerName(String handlerName) {
        if (handlerName == null) {
            return "";
        }
        String normalized = handlerName.toLowerCase();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        return normalized.replace("_", "");
    }

    @Override
    public Datum getProperty(int instanceId, String propertyName) {
        return Datum.VOID;
    }

    @Override
    public void setProperty(int instanceId, String propertyName, Datum value) {
    }

    private String generatePublicKey(InstanceState state) {
        byte[] secret = new byte[40];
        random.nextBytes(secret);
        state.privateKey = new BigInteger(1, secret).mod(P.subtract(TWO)).add(TWO);
        state.lastError = "";
        return G.modPow(state.privateKey, P).toString();
    }

    private Datum setServerPublicKey(InstanceState state, List<Datum> args) {
        if (state.privateKey == null) {
            return fail(state, "Client private key not initialised");
        }
        if (args.isEmpty() || args.get(0).toStr().isEmpty()) {
            return fail(state, "Server public key missing");
        }

        BigInteger serverPublic = new BigInteger(args.get(0).toStr());
        if (serverPublic.compareTo(TWO) < 0 || serverPublic.compareTo(P) >= 0) {
            return fail(state, "Server public key outside valid range");
        }

        BigInteger shared = serverPublic.modPow(state.privateKey, P);
        state.sharedKeyBytes = fixedUnsignedBytes(shared, DH_KEY_LENGTH);
        byte[] prk = hmacSha256(HKDF_SALT, state.sharedKeyBytes);
        state.clientToServerData = stream(prk, "bobba-c2s-data");
        state.clientToServerHeader = stream(prk, "bobba-c2s-header");
        state.serverToClientData = stream(prk, "bobba-s2c-data");
        state.serverToClientHeader = stream(prk, "bobba-s2c-header");
        state.ready = true;
        state.lastError = "";
        return Datum.TRUE;
    }

    private static Datum encrypt(InstanceState state, List<Datum> args, ChaChaStream stream, String label) {
        if (!state.ready || stream == null) {
            return fail(state, "Crypto session not ready");
        }
        Datum arg = requireStringArg(state, args, label);
        if (arg == null) {
            return Datum.ZERO;
        }
        String value = arg.toStr();
        byte[] input = stringToBytes(value);
        traceHeader(state, label, input);
        byte[] encrypted = stream.apply(input);
        String output = encodeCipherText(encrypted);
        tracePayload(state, "encrypt " + label, input, encrypted.length, output.length());
        if (TRACE_CRYPTO) {
            trace(label + " rawLen=" + input.length + " outLen=" + output.length());
        }
        return Datum.of(output);
    }

    private static Datum decrypt(InstanceState state, List<Datum> args, ChaChaStream stream, String label) {
        if (!state.ready || stream == null) {
            return fail(state, "Crypto session not ready");
        }
        Datum arg = requireStringArg(state, args, label);
        if (arg == null) {
            return Datum.ZERO;
        }
        String value = arg.toStr();
        byte[] input = decodeCipherText(value);
        if (TRACE_CRYPTO && "s2c header".equals(label) && input.length == 4) {
            trace("s2c header candidates"
                    + " c2sData=" + bytesToHex(state.clientToServerData.peek(input))
                    + " c2sHeader=" + bytesToHex(state.clientToServerHeader.peek(input))
                    + " s2cData=" + bytesToHex(state.serverToClientData.peek(input))
                    + " s2cHeader=" + bytesToHex(state.serverToClientHeader.peek(input)));
        }
        byte[] output = stream.apply(input);
        traceHeader(state, label, output);
        tracePayload(state, "decrypt " + label, output, input.length, value.length());
        if (TRACE_CRYPTO) {
            trace(label
                    + " rawLen=" + input.length
                    + " encodedLen=" + value.length()
                    + " outLen=" + output.length);
        }
        return Datum.of(bytesToString(output));
    }

    private static Datum requireStringArg(InstanceState state, List<Datum> args, String label) {
        if (args.isEmpty() || args.get(0) == null || !args.get(0).isString()) {
            fail(state, "Crypto " + label + " requires a string argument");
            return null;
        }
        return args.get(0);
    }

    private static void traceHeader(InstanceState state, String label, byte[] bytes) {
        if (!DebugConfig.isMusTraceEnabled() || bytes == null) {
            return;
        }
        if ("c2s header".equals(label)) {
            System.out.println("[BobbaXtra] encrypt c2s header"
                    + " seq=" + (++state.clientHeaderSeq)
                    + " rawLen=" + bytes.length
                    + headerValuePreview(bytes));
        } else if ("s2c header".equals(label)) {
            System.out.println("[BobbaXtra] decrypt s2c header"
                    + " seq=" + (++state.serverHeaderSeq)
                    + " rawLen=" + bytes.length
                    + headerValuePreview(bytes));
        }
    }

    private static void tracePayload(InstanceState state, String label, byte[] plainBytes,
            int cipherRawLength, int encodedLength) {
        if (!DebugConfig.isMusTraceEnabled() || bytesMissing(plainBytes) || !label.endsWith("payload")) {
            return;
        }
        boolean clientToServer = label.startsWith("encrypt c2s");
        long seq = clientToServer ? ++state.clientPayloadSeq : ++state.serverPayloadSeq;
        StringBuilder out = new StringBuilder(128);
        out.append("[BobbaXtra] ").append(label)
                .append(" seq=").append(seq)
                .append(" plainLen=").append(plainBytes.length);
        if (cipherRawLength >= 0) {
            out.append(" cipherRawLen=").append(cipherRawLength);
        }
        if (encodedLength >= 0) {
            out.append(" encodedLen=").append(encodedLength);
        }
        if (plainBytes.length >= 1) {
            out.append(" firstByteLow6=").append(fuseLow6(plainBytes[0]));
        }
        if (plainBytes.length >= 2) {
            out.append(" firstTwoLow6=").append(fuseMessageType(plainBytes));
        } else {
            out.append(" oneBytePayload=true");
        }
        out.append(" ").append(fuseMessageSequencePreview(plainBytes));
        System.out.println(out);
    }

    private static boolean bytesMissing(byte[] bytes) {
        return bytes == null;
    }

    private static int fuseMessageType(byte[] bytes) {
        return fuseMessageType(bytes, 0);
    }

    private static int fuseMessageType(byte[] bytes, int offset) {
        return (fuseLow6(bytes[offset]) * 64) | fuseLow6(bytes[offset + 1]);
    }

    static String fuseMessageSequencePreview(byte[] bytes) {
        if (bytesMissing(bytes) || bytes.length == 0) {
            return "fuse=[]";
        }
        StringBuilder out = new StringBuilder();
        out.append("fuse=[");
        int offset = 0;
        int count = 0;
        while (offset < bytes.length && count < 8) {
            if (count > 0) {
                out.append(", ");
            }
            if (bytes.length - offset < 2) {
                out.append("{tailLen=").append(bytes.length - offset)
                        .append(",tail=").append(hexPreview(bytes, offset, bytes.length - offset, 8))
                        .append('}');
                offset = bytes.length;
                break;
            }
            int terminator = findByte(bytes, offset + 2, (byte) 1);
            boolean terminated = terminator >= 0;
            int end = terminated ? terminator : bytes.length;
            int paramLen = Math.max(0, end - offset - 2);
            out.append("{type=").append(fuseMessageType(bytes, offset))
                    .append(",paramLen=").append(paramLen)
                    .append(",term=").append(terminated)
                    .append(",params=").append(asciiPreview(bytes, offset + 2, paramLen, 24))
                    .append('}');
            offset = terminated ? terminator + 1 : bytes.length;
            count++;
        }
        if (offset < bytes.length) {
            out.append(", ...");
        }
        out.append(']');
        return out.toString();
    }

    private static int findByte(byte[] bytes, int offset, byte needle) {
        for (int i = Math.max(0, offset); i < bytes.length; i++) {
            if (bytes[i] == needle) {
                return i;
            }
        }
        return -1;
    }

    private static int fuseLow6(byte value) {
        return value & 0x3f;
    }

    private static String headerValuePreview(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        out.append(" ascii=").append(asciiPreview(bytes, 0, bytes.length, 16));
        out.append(" hex=").append(hexPreview(bytes, 0, bytes.length, 16));
        if (bytes.length == 4) {
            int bigEndian = ((bytes[0] & 0xff) << 24)
                    | ((bytes[1] & 0xff) << 16)
                    | ((bytes[2] & 0xff) << 8)
                    | (bytes[3] & 0xff);
            int littleEndian = (bytes[0] & 0xff)
                    | ((bytes[1] & 0xff) << 8)
                    | ((bytes[2] & 0xff) << 16)
                    | ((bytes[3] & 0xff) << 24);
            out.append(" intBE=").append(bigEndian);
            out.append(" intLE=").append(littleEndian);
        }
        return out.toString();
    }

    public static void setRuntimeMachineSeed(String seed) {
        runtimeMachineSeed = seed != null ? seed.trim() : "";
        cachedMachineId = "";
    }

    private static String machineId() {
        String id = cachedMachineId;
        if (id == null || id.isEmpty()) {
            id = buildMachineId(resolveMachineSeed());
            cachedMachineId = id;
        }
        return id;
    }

    private static String resolveMachineSeed() {
        if (!runtimeMachineSeed.isEmpty()) {
            return runtimeMachineSeed;
        }
        String propertySeed = safeProperty(MACHINE_SEED_PROPERTY);
        if (!propertySeed.isEmpty()) {
            return propertySeed;
        }
        String legacyPropertySeed = safeProperty("libreshockwave.machineSeed");
        if (!legacyPropertySeed.isEmpty()) {
            return legacyPropertySeed;
        }

        String hostSeed = safeProperty("user.name")
                + "|"
                + safeProperty("user.home")
                + "|"
                + safeProperty("os.name")
                + "|"
                + safeProperty("os.arch");
        return hostSeed.replace("|", "").isEmpty() ? PROCESS_SEED : hostSeed;
    }

    private static String safeProperty(String name) {
        try {
            String value = System.getProperty(name);
            return value != null ? value.trim() : "";
        } catch (SecurityException e) {
            return "";
        }
    }

    private static String asciiPreview(byte[] bytes, int offset, int length, int limit) {
        if (bytes == null || length <= 0 || offset >= bytes.length) {
            return "\"\"";
        }
        int start = Math.max(0, offset);
        int available = Math.min(length, bytes.length - start);
        int count = Math.min(available, limit);
        StringBuilder out = new StringBuilder(count + 8);
        out.append('"');
        for (int i = 0; i < count; i++) {
            int v = bytes[start + i] & 0xff;
            if (v == '\\' || v == '"') {
                out.append('\\').append((char) v);
            } else if (v == '\r') {
                out.append("\\r");
            } else if (v == '\n') {
                out.append("\\n");
            } else if (v == '\t') {
                out.append("\\t");
            } else if (v >= 32 && v < 127) {
                out.append((char) v);
            } else {
                out.append("\\x");
                String hex = Integer.toHexString(v).toUpperCase();
                if (hex.length() == 1) {
                    out.append('0');
                }
                out.append(hex);
            }
        }
        if (available > count) {
            out.append("...");
        }
        out.append('"');
        return out.toString();
    }

    private static String hexPreview(byte[] bytes, int offset, int length, int limit) {
        if (bytes == null || length <= 0 || offset >= bytes.length) {
            return "";
        }
        int start = Math.max(0, offset);
        int available = Math.min(length, bytes.length - start);
        int count = Math.min(available, limit);
        StringBuilder out = new StringBuilder(count * 3 + 3);
        char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                out.append(' ');
            }
            int v = bytes[start + i] & 0xff;
            out.append(hex[v >>> 4]);
            out.append(hex[v & 0x0f]);
        }
        if (available > count) {
            out.append(" ...");
        }
        return out.toString();
    }

    private static String buildMachineId(String seed) {
        byte[] digest = Sha256.hash(machineSeedBytes(seed));
        byte[] payload = new byte[12];
        payload[0] = 1;
        System.arraycopy(digest, 0, payload, 1, 9);
        int checksum = crc16Ccitt(payload, 10);
        payload[10] = (byte) (checksum >>> 8);
        payload[11] = (byte) checksum;

        String encoded = base32MachineId(payload);
        StringBuilder id = new StringBuilder(28);
        id.append("BX1");
        for (int i = 0; i < encoded.length(); i++) {
            if (i % 4 == 0) {
                id.append('-');
            }
            id.append(encoded.charAt(i));
        }
        return id.toString();
    }

    private static byte[] machineSeedBytes(String seed) {
        String value = seed != null ? seed.trim() : "";
        if (!value.isEmpty()) {
            try {
                byte[] decoded = decodeCipherText(value);
                if (decoded.length == 32) {
                    return decoded;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return ascii(value);
    }

    private static String base32MachineId(byte[] payload) {
        StringBuilder encoded = new StringBuilder(20);
        int buffer = 0;
        int bits = 0;
        for (byte b : payload) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                encoded.append(MACHINE_ID_ALPHABET[(buffer >>> bits) & 0x1f]);
            }
        }
        if (bits > 0) {
            encoded.append(MACHINE_ID_ALPHABET[(buffer << (5 - bits)) & 0x1f]);
        }
        while (encoded.length() < 20) {
            encoded.append(MACHINE_ID_ALPHABET[0]);
        }
        if (encoded.length() > 20) {
            return encoded.substring(0, 20);
        }
        return encoded.toString();
    }

    private static int crc16Ccitt(byte[] bytes, int length) {
        int crc = 0xffff;
        for (int i = 0; i < length; i++) {
            crc ^= (bytes[i] & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
                crc &= 0xffff;
            }
        }
        return crc;
    }

    private static ChaChaStream stream(byte[] prk, String label) {
        byte[] info = concat(INFO_PREFIX, ascii(label));
        byte[] material = hkdfExpand(prk, info, 44);
        byte[] key = Arrays.copyOfRange(material, 0, 32);
        int nonce0 = littleInt(material, 32);
        int nonce1 = littleInt(material, 36);
        int nonce2 = littleInt(material, 40);
        return new ChaChaStream(key, nonce0, nonce1, nonce2);
    }

    private static Datum fail(InstanceState state, String message) {
        state.lastError = message;
        System.err.println("[BobbaXtra] " + message);
        return Datum.ZERO;
    }

    private static Datum unsupported(InstanceState state, String handlerName) {
        state.lastError = "Unsupported BobbaXtra handler: " + handlerName;
        System.err.println("[BobbaXtra] " + state.lastError);
        return Datum.VOID;
    }

    private static void trace(String message) {
        if (TRACE_CRYPTO) {
            System.out.println("[BobbaXtra] " + message);
        }
    }

    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
        byte[] out = new byte[length];
        byte[] previous = new byte[0];
        int written = 0;
        int counter = 1;
        while (written < length) {
            previous = hmacSha256(prk, concat(previous, info, new byte[] { (byte) counter }));
            int count = Math.min(previous.length, length - written);
            System.arraycopy(previous, 0, out, written, count);
            written += count;
            counter++;
        }
        return out;
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        byte[] normalizedKey = key.length > 64 ? Sha256.hash(key) : Arrays.copyOf(key, key.length);
        byte[] ipad = new byte[64];
        byte[] opad = new byte[64];
        for (int i = 0; i < 64; i++) {
            byte k = i < normalizedKey.length ? normalizedKey[i] : 0;
            ipad[i] = (byte) (k ^ 0x36);
            opad[i] = (byte) (k ^ 0x5c);
        }
        return Sha256.hash(concat(opad, Sha256.hash(concat(ipad, data))));
    }

    private static byte[] unsignedBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            return Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }

    private static byte[] fixedUnsignedBytes(BigInteger value, int length) {
        byte[] bytes = unsignedBytes(value);
        if (bytes.length == length) {
            return bytes;
        }
        byte[] fixed = new byte[length];
        int copy = Math.min(bytes.length, length);
        System.arraycopy(bytes, bytes.length - copy, fixed, length - copy, copy);
        return fixed;
    }

    private static byte[] ascii(String value) {
        byte[] bytes = new byte[value.length()];
        for (int i = 0; i < value.length(); i++) {
            bytes[i] = (byte) (value.charAt(i) & 0xff);
        }
        return bytes;
    }

    private static byte[] stringToBytes(String value) {
        byte[] bytes = new byte[value.length()];
        for (int i = 0; i < value.length(); i++) {
            bytes[i] = (byte) (value.charAt(i) & 0xff);
        }
        return bytes;
    }

    private static String bytesToString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            sb.append((char) (b & 0xff));
        }
        return sb.toString();
    }

    private static String encodeCipherText(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder((bytes.length * 4 + 2) / 3);
        int offset = 0;
        while (offset + 3 <= bytes.length) {
            int b0 = bytes[offset++] & 0xff;
            int b1 = bytes[offset++] & 0xff;
            int b2 = bytes[offset++] & 0xff;
            out.append(CIPHER_TEXT_ALPHABET[b0 >>> 2]);
            out.append(CIPHER_TEXT_ALPHABET[((b0 & 0x03) << 4) | (b1 >>> 4)]);
            out.append(CIPHER_TEXT_ALPHABET[((b1 & 0x0f) << 2) | (b2 >>> 6)]);
            out.append(CIPHER_TEXT_ALPHABET[b2 & 0x3f]);
        }
        int remaining = bytes.length - offset;
        if (remaining == 1) {
            int b0 = bytes[offset] & 0xff;
            out.append(CIPHER_TEXT_ALPHABET[b0 >>> 2]);
            out.append(CIPHER_TEXT_ALPHABET[(b0 & 0x03) << 4]);
        } else if (remaining == 2) {
            int b0 = bytes[offset] & 0xff;
            int b1 = bytes[offset + 1] & 0xff;
            out.append(CIPHER_TEXT_ALPHABET[b0 >>> 2]);
            out.append(CIPHER_TEXT_ALPHABET[((b0 & 0x03) << 4) | (b1 >>> 4)]);
            out.append(CIPHER_TEXT_ALPHABET[(b1 & 0x0f) << 2]);
        }
        return out.toString();
    }

    private static byte[] decodeCipherText(String value) {
        int length = value.length();
        while (length > 0 && value.charAt(length - 1) == '=') {
            length--;
        }
        int remainder = length % 4;
        if (remainder == 1) {
            throw new IllegalArgumentException("Invalid BobbaXtra ciphertext length");
        }
        int outputLength = (length / 4) * 3
                + (remainder == 2 ? 1 : remainder == 3 ? 2 : 0);
        byte[] out = new byte[outputLength];
        int inputOffset = 0;
        int outputOffset = 0;
        while (inputOffset + 4 <= length) {
            int c0 = decodeCipherTextChar(value.charAt(inputOffset++));
            int c1 = decodeCipherTextChar(value.charAt(inputOffset++));
            int c2 = decodeCipherTextChar(value.charAt(inputOffset++));
            int c3 = decodeCipherTextChar(value.charAt(inputOffset++));
            out[outputOffset++] = (byte) ((c0 << 2) | (c1 >>> 4));
            out[outputOffset++] = (byte) ((c1 << 4) | (c2 >>> 2));
            out[outputOffset++] = (byte) ((c2 << 6) | c3);
        }
        if (remainder == 2) {
            int c0 = decodeCipherTextChar(value.charAt(inputOffset));
            int c1 = decodeCipherTextChar(value.charAt(inputOffset + 1));
            out[outputOffset] = (byte) ((c0 << 2) | (c1 >>> 4));
        } else if (remainder == 3) {
            int c0 = decodeCipherTextChar(value.charAt(inputOffset));
            int c1 = decodeCipherTextChar(value.charAt(inputOffset + 1));
            int c2 = decodeCipherTextChar(value.charAt(inputOffset + 2));
            out[outputOffset++] = (byte) ((c0 << 2) | (c1 >>> 4));
            out[outputOffset] = (byte) ((c1 << 4) | (c2 >>> 2));
        }
        return out;
    }

    private static int decodeCipherTextChar(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a' + 26;
        }
        if (c >= '0' && c <= '9') {
            return c - '0' + 52;
        }
        if (c == '+') {
            return 62;
        }
        if (c == '/') {
            return 63;
        }
        throw new IllegalArgumentException("Invalid BobbaXtra ciphertext character");
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        char[] out = new char[bytes.length * 2];
        char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0f];
        }
        return new String(out);
    }

    private static int littleInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
            | ((bytes[offset + 1] & 0xff) << 8)
            | ((bytes[offset + 2] & 0xff) << 16)
            | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static void writeLittleInt(int value, byte[] out, int offset) {
        out[offset] = (byte) value;
        out[offset + 1] = (byte) (value >>> 8);
        out[offset + 2] = (byte) (value >>> 16);
        out[offset + 3] = (byte) (value >>> 24);
    }

    private static byte[] concat(byte[]... chunks) {
        int length = 0;
        for (byte[] chunk : chunks) {
            length += chunk.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, out, offset, chunk.length);
            offset += chunk.length;
        }
        return out;
    }

    private static final class InstanceState {
        private BigInteger privateKey;
        private byte[] sharedKeyBytes = new byte[0];
        private ChaChaStream clientToServerData;
        private ChaChaStream clientToServerHeader;
        private ChaChaStream serverToClientData;
        private ChaChaStream serverToClientHeader;
        private boolean ready;
        private String lastError = "";
        private long clientHeaderSeq;
        private long serverHeaderSeq;
        private long clientPayloadSeq;
        private long serverPayloadSeq;

        private void reset() {
            privateKey = null;
            sharedKeyBytes = new byte[0];
            clientToServerData = null;
            clientToServerHeader = null;
            serverToClientData = null;
            serverToClientHeader = null;
            ready = false;
            lastError = "";
            clientHeaderSeq = 0;
            serverHeaderSeq = 0;
            clientPayloadSeq = 0;
            serverPayloadSeq = 0;
        }
    }

    private static final class ChaChaStream {
        private static final int[] SIGMA = {
            0x61707865, 0x3320646e, 0x79622d32, 0x6b206574
        };

        private final int[] key = new int[8];
        private final int nonce0;
        private final int nonce1;
        private final int nonce2;
        private long messageCounter;

        private ChaChaStream(byte[] keyBytes, int nonce0, int nonce1, int nonce2) {
            for (int i = 0; i < key.length; i++) {
                key[i] = littleInt(keyBytes, i * 4);
            }
            this.nonce0 = nonce0;
            this.nonce1 = nonce1;
            this.nonce2 = nonce2;
        }

        private byte[] apply(byte[] input) {
            byte[] out = new byte[input.length];
            long messageNonce = messageCounter++;
            int messageNonce1 = nonce1 + (int) messageNonce;
            int messageNonce2 = nonce2 + (int) (messageNonce >>> 32);
            if (Integer.compareUnsigned(messageNonce1, nonce1) < 0) {
                messageNonce2++;
            }
            int offset = 0;
            int blockCounter = 0;
            while (offset < input.length) {
                byte[] keyStreamBlock = block(blockCounter++, messageNonce1, messageNonce2);
                int count = Math.min(64, input.length - offset);
                for (int i = 0; i < count; i++) {
                    out[offset + i] = (byte) (input[offset + i] ^ keyStreamBlock[i]);
                }
                offset += count;
            }
            return out;
        }

        private byte[] peek(byte[] input) {
            byte[] out = new byte[input.length];
            long messageNonce = messageCounter;
            int messageNonce1 = nonce1 + (int) messageNonce;
            int messageNonce2 = nonce2 + (int) (messageNonce >>> 32);
            if (Integer.compareUnsigned(messageNonce1, nonce1) < 0) {
                messageNonce2++;
            }
            int offset = 0;
            int blockCounter = 0;
            while (offset < input.length) {
                byte[] keyStreamBlock = block(blockCounter++, messageNonce1, messageNonce2);
                int count = Math.min(64, input.length - offset);
                for (int i = 0; i < count; i++) {
                    out[offset + i] = (byte) (input[offset + i] ^ keyStreamBlock[i]);
                }
                offset += count;
            }
            return out;
        }

        private byte[] block(int blockCounter, int nonce1, int nonce2) {
            int[] state = new int[16];
            state[0] = SIGMA[0];
            state[1] = SIGMA[1];
            state[2] = SIGMA[2];
            state[3] = SIGMA[3];
            System.arraycopy(key, 0, state, 4, key.length);
            state[12] = blockCounter;
            state[13] = nonce0;
            state[14] = nonce1;
            state[15] = nonce2;

            int[] working = Arrays.copyOf(state, state.length);
            for (int i = 0; i < 10; i++) {
                quarterRound(working, 0, 4, 8, 12);
                quarterRound(working, 1, 5, 9, 13);
                quarterRound(working, 2, 6, 10, 14);
                quarterRound(working, 3, 7, 11, 15);
                quarterRound(working, 0, 5, 10, 15);
                quarterRound(working, 1, 6, 11, 12);
                quarterRound(working, 2, 7, 8, 13);
                quarterRound(working, 3, 4, 9, 14);
            }

            byte[] out = new byte[64];
            for (int i = 0; i < working.length; i++) {
                writeLittleInt(working[i] + state[i], out, i * 4);
            }
            return out;
        }

        private static void quarterRound(int[] x, int a, int b, int c, int d) {
            x[a] += x[b];
            x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
            x[c] += x[d];
            x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
            x[a] += x[b];
            x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
            x[c] += x[d];
            x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
        }
    }

    private static final class Sha256 {
        private static final int[] K = {
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
            0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
            0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
            0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
            0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
            0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
            0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
            0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
        };

        private static byte[] hash(byte[] message) {
            long bitLength = (long) message.length * 8L;
            int paddedLength = message.length + 1 + 8;
            int remainder = paddedLength % 64;
            if (remainder != 0) {
                paddedLength += 64 - remainder;
            }
            byte[] padded = new byte[paddedLength];
            System.arraycopy(message, 0, padded, 0, message.length);
            padded[message.length] = (byte) 0x80;
            for (int i = 0; i < 8; i++) {
                padded[padded.length - 1 - i] = (byte) (bitLength >>> (i * 8));
            }

            int h0 = 0x6a09e667;
            int h1 = 0xbb67ae85;
            int h2 = 0x3c6ef372;
            int h3 = 0xa54ff53a;
            int h4 = 0x510e527f;
            int h5 = 0x9b05688c;
            int h6 = 0x1f83d9ab;
            int h7 = 0x5be0cd19;
            int[] w = new int[64];

            for (int chunk = 0; chunk < padded.length; chunk += 64) {
                for (int i = 0; i < 16; i++) {
                    int off = chunk + i * 4;
                    w[i] = ((padded[off] & 0xff) << 24)
                        | ((padded[off + 1] & 0xff) << 16)
                        | ((padded[off + 2] & 0xff) << 8)
                        | (padded[off + 3] & 0xff);
                }
                for (int i = 16; i < 64; i++) {
                    int s0 = Integer.rotateRight(w[i - 15], 7)
                        ^ Integer.rotateRight(w[i - 15], 18)
                        ^ (w[i - 15] >>> 3);
                    int s1 = Integer.rotateRight(w[i - 2], 17)
                        ^ Integer.rotateRight(w[i - 2], 19)
                        ^ (w[i - 2] >>> 10);
                    w[i] = w[i - 16] + s0 + w[i - 7] + s1;
                }

                int a = h0;
                int b = h1;
                int c = h2;
                int d = h3;
                int e = h4;
                int f = h5;
                int g = h6;
                int h = h7;
                for (int i = 0; i < 64; i++) {
                    int s1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25);
                    int ch = (e & f) ^ (~e & g);
                    int temp1 = h + s1 + ch + K[i] + w[i];
                    int s0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22);
                    int maj = (a & b) ^ (a & c) ^ (b & c);
                    int temp2 = s0 + maj;
                    h = g;
                    g = f;
                    f = e;
                    e = d + temp1;
                    d = c;
                    c = b;
                    b = a;
                    a = temp1 + temp2;
                }

                h0 += a;
                h1 += b;
                h2 += c;
                h3 += d;
                h4 += e;
                h5 += f;
                h6 += g;
                h7 += h;
            }

            byte[] out = new byte[32];
            writeBigInt(h0, out, 0);
            writeBigInt(h1, out, 4);
            writeBigInt(h2, out, 8);
            writeBigInt(h3, out, 12);
            writeBigInt(h4, out, 16);
            writeBigInt(h5, out, 20);
            writeBigInt(h6, out, 24);
            writeBigInt(h7, out, 28);
            return out;
        }

        private static void writeBigInt(int value, byte[] out, int offset) {
            out[offset] = (byte) (value >>> 24);
            out[offset + 1] = (byte) (value >>> 16);
            out[offset + 2] = (byte) (value >>> 8);
            out[offset + 3] = (byte) value;
        }
    }
}
