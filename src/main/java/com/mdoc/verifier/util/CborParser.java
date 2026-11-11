package com.mdoc.verifier.util;

import java.io.IOException;

import com.authlete.cbor.CBORDecoder;
import com.authlete.cbor.CBORDecoderException;
import com.authlete.cbor.CBORDecoderOptions;
import com.authlete.cbor.CBORItem;
import com.mdoc.verifier.enums.ErrorCode;
import com.mdoc.verifier.exception.MdocDecodeException;

/**
 * Bytes to exactly one CBOR item (RFC 8949 §5.1), with a header walk in front of the recursive decoder. A bean
 * and not static like {@link CborUtil}: the nesting limit comes from configuration
 * ({@code mdoc.limits.max-cbor-depth}, see {@code CborConfig}).
 */
public class CborParser {

    // No tag processors: every tag (0, 24, 1004, 18, ...) stays a CBORTaggedItem, so our code decides
    // what each tag means. The library default would e.g. turn tag 18 into a COSE object behind our back.
    private static final CBORDecoderOptions DECODER_OPTIONS = new CBORDecoderOptions();

    // RFC 8949 §3 wire format. Fixed by the standard, not configuration: other values would misread valid CBOR.
    private static final int BREAK = 0xff;              // RFC 8949 §3.2.1: ends an indefinite-length item
    private static final int AI_ONE_BYTE = 24;          // additional info 24..27: argument in 1, 2, 4, 8 bytes
    private static final int AI_RESERVED = 28;          // 28..30: reserved, not well-formed
    private static final int AI_INDEFINITE = 31;

    private final int maxDepth;

    /** @param maxDepth arrays, maps and tags nested deeper than this are rejected; range checked in LimitProperties */
    public CborParser(final int maxDepth) {
        this.maxDepth = maxDepth;
    }

    /** Decodes exactly one CBOR data item (RFC 8949 §5.1); anything after it is an error. */
    public CBORItem decode(final byte[] bytes, final String path) {
        if (bytes.length == 0) {
            throw new MdocDecodeException(ErrorCode.CBOR_MALFORMED, path, "Empty CBOR input");
        }
        // Before the recursive decoder: depth and declared lengths. The headers alone fix where the item ends.
        final int end = checkStructure(bytes, 0, 0, path);
        if (end != bytes.length) {
            throw new MdocDecodeException(ErrorCode.CBOR_TRAILING_DATA, path, "Unexpected bytes after the CBOR data item");
        }
        try {
            return new CBORDecoder(bytes, DECODER_OPTIONS).next();
        } catch (final CBORDecoderException e) {
            throw malformed(path, e.getOffset(), e.getMessage(), e);
        } catch (final IOException e) {
            throw new MdocDecodeException(ErrorCode.CBOR_MALFORMED, path, "Malformed CBOR: " + e.getMessage(), e);
        }
    }

    // TODO: later refactor. Still long for what it guards (nesting depth, declared lengths); simplify it or
    //  take the depth limit from the CBOR library instead (library change: ADR first).
    /**
     * Walks the headers of the item at {@code start} (RFC 8949 §3) without building anything and returns the
     * position after it. Checks that every declared length and count fits in the remaining input, and that
     * arrays, maps, tags and indefinite-length strings nest at most {@link #maxDepth} deep. Recursive, but the
     * depth is checked before every step down, so no input gets the call stack deeper than maxDepth frames.
     */
    int checkStructure(final byte[] bytes, final int start, final int depth, final String path) {
        if (start >= bytes.length) {
            throw malformed(path, start, "input ends inside a data item", null);
        }
        final int initialByte = bytes[start] & 0xff; // & 0xff: a Java byte is signed
        final int major = initialByte >>> 5;         // top 3 bits: the type
        final int ai = initialByte & 0x1f;           // low 5 bits: where the argument is
        int pos = start + 1;

        if (initialByte == BREAK) {
            throw malformed(path, start, "break outside an indefinite-length item", null);
        }
        // (_ ...) strings, [_ ...] arrays, {_ ...} maps: items until BREAK (RFC 8949 §3.2.2)
        if (ai == AI_INDEFINITE && major >= 2 && major <= 5) {
            checkDepth(depth, start, path);
            while (pos >= bytes.length || (bytes[pos] & 0xff) != BREAK) { // past the end: the call below reports it
                pos = checkStructure(bytes, pos, depth + 1, path);
            }
            return pos + 1;
        }
        if (ai >= AI_RESERVED) {
            throw malformed(path, start, "additional information " + ai + " is not valid for major type " + major, null);
        }

        long argument = ai;                              // 0..23: the value itself
        if (ai >= AI_ONE_BYTE) {
            final int size = 1 << (ai - AI_ONE_BYTE);    // 24..27 -> 1, 2, 4, 8 bytes follow
            if (size > bytes.length - pos) {
                throw malformed(path, start, "header argument is truncated", null);
            }
            argument = 0;
            for (int i = 0; i < size; i++) {
                argument = (argument << 8) | (bytes[pos++] & 0xff); // big-endian, unsigned up to 2^64-1
            }
        }

        final long available = bytes.length - pos;
        return switch (major) {
            // byte / text string: the argument is the content length; the content is data, skip it
            case 2, 3 -> {
                if (Long.compareUnsigned(argument, available) > 0) {
                    throw malformed(path, start, "string of " + Long.toUnsignedString(argument)
                            + " bytes, only " + available + " left", null);
                }
                yield pos + (int) argument;
            }
            // array / map: the argument is the item / pair count. Each item takes at least one byte,
            // so a count beyond the remaining bytes cannot be true.
            case 4, 5 -> {
                final boolean array = major == 4;
                if (Long.compareUnsigned(argument, array ? available : available / 2) > 0) {
                    throw malformed(path, start, (array ? "array of " : "map of ") + Long.toUnsignedString(argument)
                            + (array ? " items" : " pairs") + ", only " + available + " bytes left", null);
                }
                checkDepth(depth, start, path);
                final int items = (int) (array ? argument : 2 * argument); // map: key + value
                for (int i = 0; i < items; i++) {
                    pos = checkStructure(bytes, pos, depth + 1, path);
                }
                yield pos;
            }
            // tag: the argument is the tag number; the content is always exactly one item
            case 6 -> {
                checkDepth(depth, start, path);
                yield checkStructure(bytes, pos, depth + 1, path);
            }
            // 0, 1 integers and 7 float / simple: the argument is the value, no content follows
            default -> pos;
        };
    }

    /** Before every step into a container; this is also what bounds the recursion above. */
    private void checkDepth(final int depth, final int offset, final String path) {
        if (depth >= maxDepth) {
            throw new MdocDecodeException(ErrorCode.CBOR_TOO_DEEP, path,
                    "CBOR nested deeper than " + maxDepth + " levels at byte offset " + offset);
        }
    }

    private static MdocDecodeException malformed(final String path, final int offset, final String reason,
                                                 final Throwable cause) {
        return new MdocDecodeException(ErrorCode.CBOR_MALFORMED, path,
                "Malformed CBOR at byte offset " + offset + ": " + reason, cause);
    }
}
