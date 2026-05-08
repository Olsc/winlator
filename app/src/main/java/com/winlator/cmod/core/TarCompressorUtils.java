package com.winlator.cmod.core;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public abstract class TarCompressorUtils {
    public enum Type {XZ, ZSTD, ZIP, TAR}

    public static Type typeFromFile(String fileName) {
        if (fileName == null) return Type.ZSTD;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".zip")) return Type.ZIP;
        if (lower.endsWith(".tzst") || lower.endsWith(".zst")) return Type.ZSTD;
        if (lower.endsWith(".txz") || lower.endsWith(".xz")) return Type.XZ;
        return Type.ZSTD;
    }

    // Interface to define the exclusion filter
    public interface ExclusionFilter {
        boolean shouldInclude(File file);
    }

    public interface OnProgressListener {
        void onProgress(int progress);
    }


    private static void addFile(ArchiveOutputStream os, File file, String entryName, long[] processedBytes, long totalSize, Callback<Integer> progressCallback) {
        try {
            os.putArchiveEntry(os.createArchiveEntry(file, entryName));
            try (BufferedInputStream inStream = new BufferedInputStream(new FileInputStream(file), StreamUtils.BUFFER_SIZE)) {
                byte[] buffer = new byte[StreamUtils.BUFFER_SIZE];
                int amountRead;
                int lastProgress = -1;
                while ((amountRead = inStream.read(buffer)) != -1) {
                    os.write(buffer, 0, amountRead);
                    if (progressCallback != null && totalSize > 0) {
                        processedBytes[0] += amountRead;
                        int progress = (int) (processedBytes[0] * 100 / totalSize);
                        if (progress != lastProgress) {
                            progressCallback.call(progress);
                            lastProgress = progress;
                        }
                    }
                }
            }
            os.closeArchiveEntry();
        }
        catch (IOException e) {
            Log.e("TarCompressorUtils", "Error adding file: " + file.getPath(), e);
        }
    }

    private static void addLinkFile(ArchiveOutputStream os, File file, String entryName) {
        try {
            String linkName = FileUtils.readSymlink(file);
            if (linkName == null || linkName.isEmpty()) return;

            if (os instanceof TarArchiveOutputStream) {
                TarArchiveEntry entry = new TarArchiveEntry(entryName, TarConstants.LF_SYMLINK);
                entry.setLinkName(linkName);
                os.putArchiveEntry(entry);
                os.closeArchiveEntry();
            } else if (os instanceof ZipArchiveOutputStream) {
                ZipArchiveEntry entry = new ZipArchiveEntry(entryName);
                entry.setUnixMode(FileUtils.getUnixMode(file) | 0120000); // S_IFLNK
                os.putArchiveEntry(entry);
                os.write(linkName.getBytes());
                os.closeArchiveEntry();
            }
        }
        catch (Exception e) {
            Log.e("TarCompressorUtils", "Error adding link: " + file.getPath(), e);
        }
    }

    public static void compress(Type type, File file, File destination, int level) {
        compress(type, new File[]{file}, destination, level, null);
    }

    public static void compress(Type type, File file, File destination, int level, ExclusionFilter filter) {
        compress(type, new File[]{file}, destination, level, filter);
    }

    public static void compress(Type type, File[] files, File destination, int level, ExclusionFilter filter) {
        compress(type, files, destination, level, filter, null);
    }

    public static void compress(Type type, File[] files, File destination, int level, ExclusionFilter filter, Callback<Integer> progressCallback) {
        long totalSize = 0;
        if (progressCallback != null) {
            for (File file : files) totalSize += getTotalSize(file, filter);
        }

        long[] processedBytes = {0};
        long finalTotalSize = totalSize;

        try (ArchiveOutputStream os = getArchiveOutputStream(type, destination, level)) {
            if (os instanceof TarArchiveOutputStream) ((TarArchiveOutputStream) os).setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            for (File file : files) {
                if (filter != null && !filter.shouldInclude(file)) continue;
                compressRecursively(os, file, "", filter, processedBytes, finalTotalSize, progressCallback);
            }
            os.finish();
        } catch (IOException e) {
            Log.e("TarCompressorUtils", "Compression failed", e);
        }
    }


    private static void compressRecursively(ArchiveOutputStream tar, File file, String basePath, ExclusionFilter filter, long[] processedBytes, long totalSize, Callback<Integer> progressCallback) throws IOException {
        String entryName = basePath + file.getName();
        if (FileUtils.isSymlink(file)) {
            addLinkFile(tar, file, entryName);
        } else if (file.isDirectory()) {
            tar.putArchiveEntry(tar.createArchiveEntry(file, entryName + "/"));
            tar.closeArchiveEntry();
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (filter == null || filter.shouldInclude(child)) {
                        compressRecursively(tar, child, entryName + "/", filter, processedBytes, totalSize, progressCallback);
                    }
                }
            }
        } else if (file.isFile()) {
            addFile(tar, file, entryName, processedBytes, totalSize, progressCallback);
        }
    }

    public static long getTotalSize(File file, ExclusionFilter filter) {
        if (FileUtils.isSymlink(file)) return 0; // Symlinks take negligible space in archive entry
        if (file.isDirectory()) {
            long size = 0;
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (filter == null || filter.shouldInclude(child)) size += getTotalSize(child, filter);
                }
            }
            return size;
        } else return file.length();
    }



    public static boolean extract(Type type, Context context, String assetFile, File destination) {
        return extract(type, context, assetFile, destination, null);
    }

    public static boolean extract(Type type, Context context, String assetFile, File destination, OnExtractFileListener onExtractFileListener) {
        try {
            InputStream is = context.getAssets().open(assetFile);
            long totalSize = -1;
            try (android.content.res.AssetFileDescriptor afd = context.getAssets().openFd(assetFile)) {
                totalSize = afd.getLength();
            } catch (IOException e) {}
            return extract(type, is, destination, onExtractFileListener, null, totalSize);
        }
        catch (IOException e) {
            return false;
        }
    }

    public static boolean extract(Type type, Context context, Uri source, File destination, OnExtractFileListener onExtractFileListener, OnProgressListener onProgressListener) {
        if (source == null) return false;
        try {
            long totalSize = -1;
            InputStream is;
            if (source.toString().startsWith("/")) {
                File file = new File(source.toString());
                totalSize = file.length();
                is = new FileInputStream(file);
            } else {
                try (android.content.res.AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(source, "r")) {
                    if (afd != null) totalSize = afd.getLength();
                } catch (IOException e) {}
                is = context.getContentResolver().openInputStream(source);
            }
            return extract(type, is, destination, onExtractFileListener, onProgressListener, totalSize);
        }
        catch (IOException e) {
            return false;
        }
    }

    public static boolean extract(Type type, File source, File destination, OnExtractFileListener onExtractFileListener, OnProgressListener onProgressListener) {
        if (source == null || !source.isFile()) return false;
        try {
            return extract(type, new BufferedInputStream(new FileInputStream(source), StreamUtils.BUFFER_SIZE), destination, onExtractFileListener, onProgressListener, source.length());
        }
        catch (FileNotFoundException e) {
            return false;
        }
    }

    public static boolean extract(Type type, Context context, Uri source, File destination) {
        return extract(type, context, source, destination, null);
    }

    public static boolean extract(Type type, Context context, Uri source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null) return false;
        try {
            if (source.toString().startsWith("/")) {
                return extract(type, new FileInputStream(source.toString()), destination, onExtractFileListener);
            } else {
                return extract(type, context.getContentResolver().openInputStream(source), destination, onExtractFileListener);
            }
        }
        catch (FileNotFoundException e) {
            return false;
        }
    }

    public static boolean extract(Type type, File source, File destination) {
        return extract(type, source, destination, null);
    }

    public static boolean extract(Type type, InputStream source, File destination) {
        return extract(type, source, destination, null);
    }

    public static boolean extract(Type type, File source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null || !source.isFile()) return false;
        try {
            return extract(type, new BufferedInputStream(new FileInputStream(source), StreamUtils.BUFFER_SIZE), destination, onExtractFileListener);
        }
        catch (FileNotFoundException e) {
            return false;
        }
    }

    public static boolean extract(Type type, InputStream source, File destination, OnExtractFileListener onExtractFileListener) {
        return extract(type, source, destination, onExtractFileListener, null);
    }

    public static boolean extract(Type type, InputStream source, File destination, OnExtractFileListener onExtractFileListener, OnProgressListener onProgressListener) {
        return extract(type, source, destination, onExtractFileListener, onProgressListener, -1);
    }

    private static boolean extract(Type type, InputStream source, File destination, OnExtractFileListener onExtractFileListener, OnProgressListener onProgressListener, long totalSize) {
        if (source == null) return false;
        try (InputStream progressIn = (onProgressListener != null && totalSize > 0) ? new ProgressInputStream(source, totalSize, onProgressListener) : source;
             ArchiveInputStream archive = getArchiveInputStream(type, progressIn)) {
            org.apache.commons.compress.archivers.ArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (!archive.canReadEntryData(entry)) continue;
                File file = new File(destination, entry.getName());

                if (onExtractFileListener != null) {
                    file = onExtractFileListener.onExtractFile(file, entry.getSize());
                    if (file == null) continue;
                }

                if (entry.isDirectory()) {
                    if (!file.isDirectory()) file.mkdirs();
                }
                else {
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    if (entry instanceof TarArchiveEntry && ((TarArchiveEntry) entry).isSymbolicLink()) {
                        FileUtils.symlink(((TarArchiveEntry) entry).getLinkName(), file.getAbsolutePath());
                    } else if (entry instanceof ZipArchiveEntry && ((ZipArchiveEntry) entry).isUnixSymlink()) {
                        byte[] buffer = StreamUtils.copyToByteArray(archive);
                        FileUtils.symlink(new String(buffer), file.getAbsolutePath());
                    }
                    else {
                        try (BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(file), StreamUtils.BUFFER_SIZE)) {
                            if (!StreamUtils.copy(archive, outStream)) return false;
                        }
                    }
                }

                if (!FileUtils.isSymlink(file)) FileUtils.chmod(file, 0700);
            }
            return true;
        }
        catch (IOException e) {
            String msg = e.getMessage();
            if (!(msg != null && (msg.contains("Input is not in the XZ format") || msg.contains("Not in Zstandard format")))) {
                e.printStackTrace();
            }
            return false;
        }
    }

    private static class ProgressInputStream extends InputStream {
        private final InputStream in;
        private final long totalSize;
        private final OnProgressListener listener;
        private long bytesRead = 0;
        private int lastProgress = -1;

        public ProgressInputStream(InputStream in, long totalSize, OnProgressListener listener) {
            this.in = in;
            this.totalSize = totalSize;
            this.listener = listener;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b != -1) updateProgress(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = in.read(b, off, len);
            if (read != -1) updateProgress(read);
            return read;
        }

        private void updateProgress(int read) {
            bytesRead += read;
            int progress = (int) (bytesRead * 100 / totalSize);
            if (progress != lastProgress) {
                lastProgress = progress;
                listener.onProgress(progress);
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private static ArchiveInputStream getArchiveInputStream(Type type, InputStream source) throws IOException {
        if (type == Type.XZ) {
            return new TarArchiveInputStream(new XZCompressorInputStream(source));
        } else if (type == Type.ZSTD) {
            return new TarArchiveInputStream(new ZstdCompressorInputStream(source));
        } else if (type == Type.ZIP) {
            return new ZipArchiveInputStream(source);
        }
        return null;
    }

    private static ArchiveOutputStream getArchiveOutputStream(Type type, File destination, int level) throws IOException {
        OutputStream out = new BufferedOutputStream(new FileOutputStream(destination), StreamUtils.BUFFER_SIZE);
        if (type == Type.XZ) {
            return new TarArchiveOutputStream(new XZCompressorOutputStream(out, level));
        } else if (type == Type.ZSTD) {
            return new TarArchiveOutputStream(new ZstdCompressorOutputStream(out, level));
        } else if (type == Type.ZIP) {
            ZipArchiveOutputStream zos = new ZipArchiveOutputStream(out);
            zos.setLevel(level);
            return zos;
        } else if (type == Type.TAR) {
            TarArchiveOutputStream tos = new TarArchiveOutputStream(out);
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            return tos;
        }
        return null;
    }

    public static void archive(File[] files, File destination, ExclusionFilter filter) {
        try (ArchiveOutputStream os = getArchiveOutputStream(Type.TAR, destination, 0)) {
            for (File file : files) {
                if (filter != null && !filter.shouldInclude(file)) continue;
                compressRecursively(os, file, "", filter, new long[]{0}, 0, null);
            }
            os.finish();
        } catch (IOException e) {
            Log.e("TarCompressorUtils", "Archive failed", e);
        }
    }


    public static boolean extractTar(File source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null || !source.isFile()) return false;
        try (InputStream inStream = new BufferedInputStream(new FileInputStream(source), StreamUtils.BUFFER_SIZE);
             TarArchiveInputStream tar = new TarArchiveInputStream(inStream)) {
            TarArchiveEntry entry;
            String topLevelDirectory = null;
            while ((entry = (TarArchiveEntry) tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) continue;

                // Get the top-level directory name
                String entryName = entry.getName();
                if (topLevelDirectory == null) {
                    if (entry.isDirectory()) {
                        topLevelDirectory = entryName;
                        continue; // Skip creating the top-level directory
                    }
                }

                // Skip the entire tmp directory
                if (entryName.contains("/tmp/")) {
                    Log.d("RestoreOp", "Skipping tmp directory: " + entryName);
                    continue;
                }

                // Adjust the extraction path to remove the top-level directory
                String adjustedName = entryName.replaceFirst("^" + topLevelDirectory, "");
                File file = new File(destination, adjustedName);

                if (onExtractFileListener != null) {
                    file = onExtractFileListener.onExtractFile(file, entry.getSize());
                    if (file == null) continue;
                }

                if (entry.isDirectory()) {
                    if (!file.isDirectory()) file.mkdirs();
                } else {
                    if (entry.isSymbolicLink()) {
                        FileUtils.symlink(entry.getLinkName(), file.getAbsolutePath());
                    } else {
                        try (BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(file), StreamUtils.BUFFER_SIZE)) {
                            if (!StreamUtils.copy(tar, outStream)) return false;
                        }
                    }
                }

                FileUtils.chmod(file, 0771);
            }
            return true;
        } catch (IOException e) {
            Log.e("RestoreOp", "Failed to extract tar file", e);
            return false;
        }
    }


}







