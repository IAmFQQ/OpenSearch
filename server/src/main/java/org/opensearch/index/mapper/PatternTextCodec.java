/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.mapper;

import org.opensearch.common.collect.Tuple;
import org.opensearch.common.hash.MurmurHash3;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes/decodes text into a pattern representation: template tokens (words without
 * any digit characters) and arguments (words containing at least one digit).
 * <p>
 * Binary encoding format (all multi-byte values big-endian):
 * <ul>
 *   <li><b>Template:</b> [numTokens:short][pos:short]*[len:short][UTF-8 bytes]*</li>
 *   <li><b>Args:</b> [numArgs:short][len:short][UTF-8 bytes]*</li>
 * </ul>
 */
final class PatternTextCodec {

    private PatternTextCodec() {}

    /**
     * Encodes text into (template_bytes, args_bytes).
     */
    static Tuple<byte[], byte[]> encode(String text) {
        String[] tokens = tokenize(text);
        List<Integer> templatePositions = new ArrayList<>();
        List<String> templateTokens = new ArrayList<>();
        List<String> args = new ArrayList<>();

        for (int i = 0; i < tokens.length; i++) {
            if (isArg(tokens[i])) {
                args.add(tokens[i]);
            } else {
                templatePositions.add(i);
                templateTokens.add(tokens[i]);
            }
        }

        return new Tuple<>(encodeTemplate(templatePositions, templateTokens), encodeArgs(args));
    }

    /**
     * Decodes the pattern back into full text.
     */
    static String decode(byte[] templateBytes, byte[] argsBytes) {
        return decode(templateBytes, 0, templateBytes != null ? templateBytes.length : 0,
                      argsBytes, 0, argsBytes != null ? argsBytes.length : 0);
    }

    static String decode(byte[] templateBytes, int tplOff, int tplLen,
                         byte[] argsBytes, int argsOff, int argsLen) {
        int[] positions;
        String[] templateTokens;
        String[] argValues;

        if (tplLen > 0) {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(templateBytes, tplOff, tplLen));
            try {
                int numTokens = dis.readUnsignedShort();
                positions = new int[numTokens];
                for (int i = 0; i < numTokens; i++) {
                    positions[i] = dis.readUnsignedShort();
                }
                templateTokens = new String[numTokens];
                for (int i = 0; i < numTokens; i++) {
                    int len = dis.readUnsignedShort();
                    byte[] utf8 = new byte[len];
                    dis.readFully(utf8);
                    templateTokens[i] = new String(utf8, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                return "";
            }
        } else {
            positions = new int[0];
            templateTokens = new String[0];
        }

        if (argsLen > 0) {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(argsBytes, argsOff, argsLen));
            try {
                int numArgs = dis.readUnsignedShort();
                argValues = new String[numArgs];
                for (int i = 0; i < numArgs; i++) {
                    int len = dis.readUnsignedShort();
                    byte[] utf8 = new byte[len];
                    dis.readFully(utf8);
                    argValues[i] = new String(utf8, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                return "";
            }
        } else {
            argValues = new String[0];
        }

        if (positions.length == 0 && argValues.length == 0) {
            return "";
        }

        // Reconstruct by interleaving template tokens and args at correct positions
        int totalTokens = (positions.length > 0 ? positions[positions.length - 1] + 1 : 0) + argValues.length;
        StringBuilder result = new StringBuilder();
        int tplIdx = 0;
        int argIdx = 0;
        for (int pos = 0; pos < totalTokens; pos++) {
            if (tplIdx < positions.length && positions[tplIdx] == pos) {
                if (result.length() > 0) result.append(' ');
                result.append(templateTokens[tplIdx]);
                tplIdx++;
            } else if (argIdx < argValues.length) {
                if (result.length() > 0) result.append(' ');
                result.append(argValues[argIdx]);
                argIdx++;
            }
        }
        return result.toString();
    }

    /**
     * Returns the template tokens (words without digits) for indexing.
     */
    static String[] templateTokens(String text) {
        String[] tokens = tokenize(text);
        List<String> result = new ArrayList<>();
        for (String token : tokens) {
            if (isArg(token) == false) {
                result.add(token);
            }
        }
        return result.toArray(new String[0]);
    }

    /**
     * Computes a 64-bit hash of the template text using MurmurHash3.
     */
    static long templateHash(String text) {
        String[] tokens = tokenize(text);
        StringBuilder templateOnly = new StringBuilder();
        for (String token : tokens) {
            if (isArg(token) == false) {
                if (templateOnly.length() > 0) {
                    templateOnly.append(' ');
                }
                templateOnly.append(token);
            }
        }
        byte[] bytes = templateOnly.toString().getBytes(StandardCharsets.UTF_8);
        MurmurHash3.Hash128 hash = new MurmurHash3.Hash128();
        MurmurHash3.hash128(bytes, 0, bytes.length, 0, hash);
        return hash.h1;
    }

    private static String[] tokenize(String text) {
        return text.split("\\s+");
    }

    private static boolean isArg(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c >= '0' && c <= '9') {
                return true;
            }
        }
        return false;
    }

    private static byte[] encodeTemplate(List<Integer> positions, List<String> tokens) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        try {
            dos.writeShort(positions.size());
            for (int pos : positions) {
                dos.writeShort(pos);
            }
            for (String token : tokens) {
                byte[] utf8 = token.getBytes(StandardCharsets.UTF_8);
                dos.writeShort(utf8.length);
                dos.write(utf8);
            }
        } catch (IOException e) {
            throw new AssertionError("ByteArrayOutputStream should never throw IOException", e);
        }
        return bos.toByteArray();
    }

    private static byte[] encodeArgs(List<String> args) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        try {
            dos.writeShort(args.size());
            for (String arg : args) {
                byte[] utf8 = arg.getBytes(StandardCharsets.UTF_8);
                dos.writeShort(utf8.length);
                dos.write(utf8);
            }
        } catch (IOException e) {
            throw new AssertionError("ByteArrayOutputStream should never throw IOException", e);
        }
        return bos.toByteArray();
    }
}
