package org.rundeck.plugin.scm.p4

/**
 * Created by jamie.penman on 05/12/2016.
 */

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.exec.CommandLine
import org.apache.commons.exec.DefaultExecutor
import org.apache.commons.exec.PumpStreamHandler
import org.apache.commons.io.FileUtils
import org.apache.commons.io.output.ByteArrayOutputStream
import org.apache.log4j.Logger

import java.util.regex.Matcher
import java.util.regex.Pattern

class SimpleTestServer {

    private static Logger logger = Logger.getLogger(this.class)

    private static final String RESOURCES = "src/test/resources/"

    private final String p4d
    private final File p4root

    SimpleTestServer(String root, String version) {
        String p4d = new File(RESOURCES + version).getAbsolutePath().toString()
        String os = System.getProperty("os.name").toLowerCase()
        if (os.contains("win")) {
            p4d += "/bin.ntx64/p4d.exe"
        }
        if (os.contains("mac")) {
            p4d += "/bin.darwin90x86_64/p4d"
        }
        if (os.contains("nix") || os.contains("nux")) {
            p4d += "/bin.linux26x86_64/p4d"
        }
        this.p4d = p4d
        this.p4root = new File(root).getAbsoluteFile()
    }

    String getResources() {
        return RESOURCES
    }

    String getRshPort() {
        String rsh = "p4jrsh://" + p4d
        rsh += " -r " + p4root
        rsh += " -L p4d.log"
        rsh += " -i"
        rsh += " --java"

        return rsh
    }

    void upgrade() throws Exception {
        exec(["-xu"])
    }

    void unicode() throws Exception {
        exec(["-xi"])
    }

    void restore(File ckp) throws Exception {
        exec(["-jr", "-z", formatPath(ckp.getAbsolutePath())])
    }

    void extract(File archive) throws Exception {
        TarArchiveInputStream tarIn = null
        tarIn = new TarArchiveInputStream(
                new GzipCompressorInputStream(new BufferedInputStream(new FileInputStream(archive))))

        TarArchiveEntry tarEntry = tarIn.getNextTarEntry()
        while (tarEntry != null) {
            File node = new File(p4root, tarEntry.getName())
            logger.debug("extracting: " + node.getCanonicalPath())
            if (tarEntry.isDirectory()) {
                node.mkdirs()
            } else {
                node.createNewFile()
                byte[] buf = new byte[1024]
                BufferedOutputStream bout = new BufferedOutputStream(new FileOutputStream(node))

                int len = 0
                while ((len = tarIn.read(buf)) != -1) {
                    bout.write(buf, 0, len)
                }
                bout.close()
            }
            tarEntry = tarIn.getNextTarEntry()
        }
        tarIn.close()
    }

    void clean() throws IOException {
        if (p4root.exists()) {
            FileUtils.cleanDirectory(p4root)
        } else {
            p4root.mkdir()
        }
    }

    void destroy() throws IOException {
        if (p4root.exists()) {
            FileUtils.deleteDirectory(p4root)
        }
    }

    int getVersion() throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream()
        CommandLine cmdLine = new CommandLine(p4d)
        cmdLine.addArgument("-V")
        DefaultExecutor executor = new DefaultExecutor()
        PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream)
        executor.setStreamHandler(streamHandler)
        executor.execute(cmdLine)

        int version = 0
        for (String line : outputStream.toString().split("\\n")) {
            if (line.startsWith("Rev. P4D")) {
                Pattern p = Pattern.compile("\\d{4}\\.\\d{1}")
                Matcher m = p.matcher(line)
                while (m.find()) {
                    String found = m.group()
                    found = found.replace(".", ""); // strip "."
                    version = Integer.parseInt(found)
                }
            }
        }
        logger.info("P4D Version: " + version)
        return version
    }

    private int exec(String[] args) throws Exception {
        CommandLine cmdLine = new CommandLine(p4d)
        cmdLine.addArgument("-C1")
        cmdLine.addArgument("-r")
        cmdLine.addArgument(formatPath(p4root.getAbsolutePath()))
        for (String arg : args) {
            cmdLine.addArgument(arg)
        }

        logger.debug("EXEC: " + cmdLine.toString())

        DefaultExecutor executor = new DefaultExecutor()
        return executor.execute(cmdLine)
    }

    private String formatPath(String path) {
        final String Q = "\""
        path = Q + path + Q
        String os = System.getProperty("os.name").toLowerCase()
        if (os.contains("win")) {
            path = path.replace('\\', '/')
        }
        return path
    }
}