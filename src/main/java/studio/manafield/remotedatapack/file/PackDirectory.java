package studio.manafield.remotedatapack.file;

import studio.manafield.remotedatapack.auth.AuthFailure;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/** All content I/O and rename/unlink use a descriptor-relative directory, never a request path. */
public final class PackDirectory implements AutoCloseable {
    private final Path root;
    private final SecureDirectoryStream<Path> directory;
    public PackDirectory(Path root) throws IOException {
        if(root==null) throw new AuthFailure(503,"files_unavailable");
        this.root=root; directory=open(root);
    }
    private static SecureDirectoryStream<Path> open(Path root) throws IOException {
        DirectoryStream<Path> initial=Files.newDirectoryStream(root.getRoot());
        if(!(initial instanceof SecureDirectoryStream<Path> current)) { initial.close(); throw new AuthFailure(503,"unsupported_filesystem"); }
        try {
            for(Path component:root) { var next=current.newDirectoryStream(component,NOFOLLOW_LINKS); current.close(); current=next; }
            return current;
        } catch(IOException|RuntimeException e) { current.close(); throw e; }
    }
    public void verifyRoot() throws IOException {
        try(var current=open(root)) {
            Object key=directory.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey();
            if(key==null || !key.equals(current.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey())) conflict();
        }
    }
    private static Path leaf(String name) {
        if(!studio.manafield.remotedatapack.datapack.LocalPackScanner.safeLeaf(name)) throw new AuthFailure(400,"invalid_file_id");
        return Path.of(name);
    }
    public BasicFileAttributes attributes(String name) throws IOException {
        return directory.getFileAttributeView(leaf(name),BasicFileAttributeView.class,NOFOLLOW_LINKS).readAttributes();
    }
    public void safe(String name) throws IOException {
        verifyRoot(); var attr=attributes(name);
        var unix=Files.readAttributes(root.resolve(leaf(name)),"unix:nlink,fileKey",NOFOLLOW_LINKS);
        if(!attr.isRegularFile() || attr.isSymbolicLink() || attr.fileKey()==null || !attr.fileKey().equals(unix.get("fileKey"))
                || !(unix.get("nlink") instanceof Number count) || count.longValue()!=1) throw new AuthFailure(409,"unsafe_file");
    }
    public boolean exists(String name) throws IOException {
        try { attributes(name); return true; } catch(NoSuchFileException e) { return false; }
    }
    public SeekableByteChannel create(String name) throws IOException {
        verifyRoot(); return directory.newByteChannel(leaf(name),Set.of(CREATE_NEW,READ,WRITE,NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }
    public SeekableByteChannel read(String name) throws IOException {
        safe(name); Object before=attributes(name).fileKey();
        var channel=directory.newByteChannel(leaf(name),Set.of(READ,NOFOLLOW_LINKS));
        try { safe(name); if(!before.equals(attributes(name).fileKey())) conflict(); return channel; }
        catch(IOException|RuntimeException e) { channel.close(); throw e; }
    }
    public String digest(String name,long max,Runnable check) throws IOException {
        try(var input=read(name)) { return digest(input,max,check); }
    }
    static String digest(SeekableByteChannel input,long max,Runnable check) throws IOException {
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256"); input.position(0); ByteBuffer buffer=ByteBuffer.allocate(8192); long total=0;
            while(input.read(buffer)!=-1) { check.run(); total+=buffer.position(); if(total>max) throw new AuthFailure(413,"file_too_large"); buffer.flip(); hash.update(buffer); buffer.clear(); }
            return HexFormat.of().formatHex(hash.digest());
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public void copy(String from,String to,long max,Runnable check) throws IOException {
        try(var input=read(from); var output=create(to)) {
            ByteBuffer buffer=ByteBuffer.allocate(8192); long total=0;
            while(input.read(buffer)!=-1) { check.run(); total+=buffer.position(); if(total>max) throw new AuthFailure(413,"file_too_large"); buffer.flip(); while(buffer.hasRemaining()) output.write(buffer); buffer.clear(); }
            force(output);
        } catch(IOException|RuntimeException e) { removeQuietly(to); throw e; }
    }
    public void move(String from,String to) throws IOException { directory.move(leaf(from),directory,leaf(to)); }
    public void remove(String name) throws IOException { directory.deleteFile(leaf(name)); }
    public void removeQuietly(String name) { if(name!=null) try { remove(name); } catch(IOException|RuntimeException ignored) { } }
    public void cleanAbandonedTemps() throws IOException {
        try(var listing=open(root)) {
            int visited=0;
            for(Path path:listing) {
                if(++visited>4096) throw new AuthFailure(503,"directory_limit");
                String name=path.getFileName().toString();
                if(name.matches("\\.rd-tmp-[a-f0-9-]{36}\\.tmp")) { safe(name); remove(name); }
            }
        }
    }
    public record Backup(String name,long bytes,long modified) { }
    public List<Backup> backups() throws IOException {
        List<Backup> result=new ArrayList<>(); int visited=0;
        // A fresh stream is needed: DirectoryStream supports exactly one iterator.
        try(var listing=open(root)) {
            for(Path path:listing) {
                if(++visited>4096) throw new AuthFailure(503,"directory_limit");
                String name=path.getFileName().toString();
                if(name.matches("\\.rd-backup-[a-f0-9]{64}-[a-f0-9-]{36}\\.bak")) {
                    safe(name); var attr=attributes(name); result.add(new Backup(name,attr.size(),attr.lastModifiedTime().toMillis()));
                }
            }
        }
        result.sort(Comparator.comparingLong(Backup::modified).thenComparing(Backup::name)); return result;
    }
    public static void force(SeekableByteChannel output) throws IOException {
        if(!(output instanceof FileChannel channel)) throw new AuthFailure(503,"unsupported_filesystem"); channel.force(true);
    }
    private static void conflict() { throw new AuthFailure(409,"filesystem_changed"); }
    @Override public void close() throws IOException { directory.close(); }
}
