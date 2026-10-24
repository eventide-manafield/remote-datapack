package studio.manafield.remotedatapack.file;

import studio.manafield.remotedatapack.auth.AuthFailure;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.zip.*;

/** Does not extract files. Bounded central-directory checks plus streaming CRC/actual-size checks. */
public final class ZipValidator {
    private final studio.manafield.remotedatapack.datapack.PackFormat target;
    public ZipValidator(studio.manafield.remotedatapack.datapack.PackFormat target) { this.target = java.util.Objects.requireNonNull(target); }
    private static final long MAX_EXPANDED = 128L * 1048576;
    private static final int MAX_ENTRIES = 10000, MAX_METADATA = 65536;
    private record Entry(String name, long size, long compressed, long crc, int method, long offset) { }
    public void validate(SeekableByteChannel channel, Runnable check) throws IOException {
        try {
            long size = channel.size();
            if (size < 22 || size > 20L*1048576) fail();
            // Conservative MVP: no archive comment, ZIP64, spanning, encryption or exotic extra fields.
            ByteBuffer end = read(channel, size-22, 22);
            if (end.getInt(0)!=0x06054b50 || end.getShort(4)!=0 || end.getShort(6)!=0 || end.getShort(20)!=0) fail();
            int count = Short.toUnsignedInt(end.getShort(10));
            long centralSize = Integer.toUnsignedLong(end.getInt(12)), start = Integer.toUnsignedLong(end.getInt(16));
            if (count < 1 || count>MAX_ENTRIES || count != Short.toUnsignedInt(end.getShort(8)) || start+centralSize != size-22 || centralSize>4*1048576) fail();
            ByteBuffer central = read(channel,start,(int)centralSize);
            List<Entry> entries = new ArrayList<>(); Set<String> names = new HashSet<>(); long expanded=0, pos=0;
            for(int i=0;i<count;i++) {
                check.run(); if(central.remaining()<46) fail(); int p=central.position();
                if(central.getInt(p)!=0x02014b50) fail();
                int flags=Short.toUnsignedInt(central.getShort(p+8)), method=Short.toUnsignedInt(central.getShort(p+10));
                long compressed=Integer.toUnsignedLong(central.getInt(p+20)), uncompressed=Integer.toUnsignedLong(central.getInt(p+24));
                int nameLength=Short.toUnsignedInt(central.getShort(p+28)), extra=Short.toUnsignedInt(central.getShort(p+30)), comment=Short.toUnsignedInt(central.getShort(p+32));
                int mode=(central.getInt(p+38) >>> 16)&0170000;
                long localOffset=Integer.toUnsignedLong(central.getInt(p+42));
                if((flags & ~(8|2048))!=0 || (method!=0 && method!=8) || central.getShort(p+34)!=0
                        || (mode!=0 && mode!=0100000 && mode!=0040000) || nameLength<1 || nameLength>1024
                        || central.remaining()<46+nameLength+extra+comment || uncompressed>MAX_EXPANDED
                        || uncompressed>Math.max(1,compressed)*100 || (expanded+=uncompressed)>MAX_EXPANDED || localOffset!=pos) fail();
                byte[] rawName=new byte[nameLength]; central.position(p+46); central.get(rawName);
                String name=decode(rawName); safeName(name); if(!names.add(name)) fail();
                // Reject Unix link metadata and ZIP64, even if mode bits have been disguised.
                int extraEnd=central.position()+extra;
                while(central.position()<extraEnd) {
                    if(extraEnd-central.position()<4) fail(); int tag=Short.toUnsignedInt(central.getShort()), len=Short.toUnsignedInt(central.getShort());
                    if(central.position()+len>extraEnd || (tag!=0x5455 && tag!=0x7875 && tag!=0x000a)) fail();
                    central.position(central.position()+len);
                }
                central.position(extraEnd+comment);
                ByteBuffer local=read(channel,localOffset,30);
                if(local.getInt(0)!=0x04034b50 || local.getShort(6)!=(short)flags || local.getShort(8)!=(short)method
                        || Short.toUnsignedInt(local.getShort(26))!=nameLength) fail();
                int localExtra=Short.toUnsignedInt(local.getShort(28));
                if(!Arrays.equals(rawName,read(channel,localOffset+30,nameLength).array())) fail();
                // Local extras are checked independently; CEN and LOC may differ.
                ByteBuffer lx=read(channel,localOffset+30+nameLength,localExtra);
                while(lx.hasRemaining()) { if(lx.remaining()<4) fail(); int tag=Short.toUnsignedInt(lx.getShort()), len=Short.toUnsignedInt(lx.getShort());
                    if(len>lx.remaining() || (tag!=0x5455 && tag!=0x7875 && tag!=0x000a)) fail(); lx.position(lx.position()+len); }
                pos=localOffset+30+nameLength+localExtra+compressed;
                if((flags&8)!=0) {
                    ByteBuffer descriptor=read(channel,pos,16); int shift=descriptor.getInt(0)==0x08074b50?4:0;
                    if(descriptor.getInt(shift)!=central.getInt(p+16) || Integer.toUnsignedLong(descriptor.getInt(shift+4))!=compressed
                            || Integer.toUnsignedLong(descriptor.getInt(shift+8))!=uncompressed) fail(); pos+=12+shift;
                } else if(local.getInt(14)!=central.getInt(p+16) || Integer.toUnsignedLong(local.getInt(18))!=compressed
                        || Integer.toUnsignedLong(local.getInt(22))!=uncompressed) fail();
                if(pos>start) fail();
                entries.add(new Entry(name,uncompressed,compressed,Integer.toUnsignedLong(central.getInt(p+16)),method,localOffset));
            }
            if(central.hasRemaining() || pos!=start || !names.contains("pack.mcmeta")) fail();
            channel.position(0);
            InputStream nonClosing=new FilterInputStream(Channels.newInputStream(channel)) { @Override public void close() { } };
            byte[] buffer=new byte[8192]; byte[] metadata=null; long actualTotal=0;
            try(var zip=new ZipInputStream(nonClosing,StandardCharsets.UTF_8)) {
                for(Entry expected:entries) {
                    check.run(); ZipEntry entry=zip.getNextEntry(); if(entry==null || !entry.getName().equals(expected.name)) fail();
                    boolean meta=expected.name.equals("pack.mcmeta");
                    if(meta && expected.size>MAX_METADATA) fail();
                    var content=meta?new ByteArrayOutputStream():null; long actual=0; int n;
                    while((n=zip.read(buffer))!=-1) {
                        check.run(); actual+=n; actualTotal+=n;
                        if(actual>expected.size || actualTotal>MAX_EXPANDED || (meta && actual>MAX_METADATA)) fail();
                        if(meta) content.write(buffer,0,n);
                    }
                    if(actual!=expected.size || entry.getCrc()!=expected.crc) fail();
                    if(meta) metadata=content.toByteArray();
                }
                if(zip.getNextEntry()!=null) fail();
            }
            metadata(metadata);
        } catch (AuthFailure e) { throw e; }
        catch (IllegalArgumentException | BufferUnderflowException | IndexOutOfBoundsException | CharacterCodingException e) { fail(); }
        catch (ZipException | EOFException e) { fail(); }
    }
    private static ByteBuffer read(SeekableByteChannel channel,long offset,int count) throws IOException {
        if(offset<0 || count<0 || offset+count>channel.size()) throw new EOFException();
        channel.position(offset); ByteBuffer buffer=ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN);
        while(buffer.hasRemaining()) if(channel.read(buffer)<0) throw new EOFException();
        return buffer.flip();
    }
    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static void safeName(String name) {
        if(name.startsWith("/") || name.contains("\\") || name.contains(":") || name.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT)) fail();
        String[] parts=name.split("/",-1);
        for(int i=0;i<parts.length;i++) if(parts[i].equals(".") || parts[i].equals("..") || (parts[i].isEmpty() && i!=parts.length-1)) fail();
    }
    private void metadata(byte[] bytes) throws CharacterCodingException {
        if(bytes==null) fail(); Object json=MetadataJson.parse(decode(bytes));
        if(!(json instanceof Map<?,?> root) || !(root.get("pack") instanceof Map<?,?> pack) || !pack.containsKey("description")) fail();
        Map<?,?> pack=(Map<?,?>)((Map<?,?>)json).get("pack");
        Object description=pack.get("description");
        if(!(description instanceof String || description instanceof Map<?,?> || description instanceof List<?>)) fail();
        // Integer max_format includes all minor versions of that major (Minecraft 1.21.9+).
        long min=format(pack.get("min_format"), false), max=format(pack.get("max_format"), true);
        if(min>target.number() || max<target.number() || min>max) throw new AuthFailure(422,"incompatible_pack_format");
    }
    private static long format(Object value, boolean maximum) {
        Object major=value, minor=maximum ? BigDecimal.valueOf(999999) : BigDecimal.ZERO;
        if(value instanceof List<?> list && list.size()==2) { major=list.get(0); minor=list.get(1); }
        if(!(major instanceof BigDecimal) || !(minor instanceof BigDecimal)) fail();
        try { long a=((BigDecimal)major).longValueExact(), b=((BigDecimal)minor).longValueExact();
            if(a<0 || a>100000 || b<0 || b>=1000000) fail(); return a*1000000+b;
        } catch(ArithmeticException e) { fail(); return 0; }
    }
    private static void fail() { throw new AuthFailure(422,"invalid_zip"); }
}
