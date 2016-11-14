package org.rundeck.plugin.scm.p4

import com.perforce.p4java.client.IClient
import com.perforce.p4java.core.*
import com.perforce.p4java.core.file.FileSpecBuilder
import com.perforce.p4java.core.file.IFileSpec
import com.perforce.p4java.impl.generic.core.Label
import com.perforce.p4java.impl.mapbased.server.Server
import com.perforce.p4java.server.CmdSpec
import com.perforce.p4java.server.IServer
import difflib.DiffUtils
import difflib.Patch

/**
 * Created by greg on 9/10/15.
 */
class P4Util {

    /**
     * Get the changelist for the HEAD rev of the path
     *
     * @return the changelist summary or null if no HEAD revision exists
     */
    static IChangelistSummary getHead(IServer p4Server, IClient p4Client) {
        // Get the #head changelist affecting files on this client
        List<IChangelistSummary> changes = p4Client.getChangelists(1,
                FileSpecBuilder.makeFileSpecList("//" + p4Client.getName() + "/..."),
                p4Client.getName(), p4Server.getUserName(), true, false, false, true)
        return changes.isEmpty() ? null : changes.get(0)
    }
    /**
     * get RevCommit for HEAD rev of the path
     * @return RevCommit or null if HEAD not found (empty p4)
     */
    static IChangelistSummary getCommit(IServer p4Server, int commit) {
        IChangelistSummary change = p4Server.getChangelist(commit)
        if (change.getStatus() != ChangelistStatus.SUBMITTED) {
            return null
        }
        return change
    }

    /**
     * print diff to output stream
     * @param out stream, or null to simply return count of differences
     * @param leftSide
     * @param rightSide
     * @param COMP
     * @return
     */
    static int diffContent(
            OutputStream out,
            byte[] leftSide,
            File rightSide
    )
    {
        String leftSideStr = new String(leftSide)
        def leftLines
        def rightLines
        leftLines = leftSideStr.readLines()
        rightSide.eachLine { line -> rightLines << line }
        return diffContent(out, leftLines, rightLines)
    }

    /**
     * print diff to output stream
     * @param out stream, or null to simply return count of differences
     * @param leftSide
     * @param rightSide
     * @param COMP
     * @return
     */
    static int diffContent(
            OutputStream out,
            File leftSide,
            byte[] rightSide
    )
    {

        String rightSideStr = new String(rightSide)
        def leftLines
        def rightLines
        rightLines = rightSideStr.readLines()
        leftSide.eachLine { line -> leftLines << line }
        return diffContent(out, leftLines, rightLines)
    }

    /**
     * print diff to output stream
     * @param out stream, or null to simply return count of differences
     * @param leftSide
     * @param rightSide
     * @param COMP
     * @return
     */
    static int diffContent(
            OutputStream out,
            byte[] leftSide,
            byte[] rightSide
    )
    {
        String leftSideStr  = new String(leftSide)
        String rightSideStr = new String(rightSide)
        def leftLines       = leftSideStr.readLines()
        def rightLines      = rightSideStr.readLines()
        return diffContent(out, leftLines, rightLines)
    }

    /**
     * print diff to output stream
     * @param out stream, or null to simply return count of differences
     * @param leftSide
     * @param rightSide
     * @param COMP
     * @return
     */
    static int diffContent(OutputStream out, List<String> leftLines, List<String> rightLines) {
        Patch patch = DiffUtils.diff(leftLines, rightLines)
        // TODO: Need to use correct left/right filenames, not just left and right...
        List<String> unifiedDiff = DiffUtils.generateUnifiedDiff("left", "right", leftLines, patch, 4)
        if (!unifiedDiff.isEmpty() && out != null) {
            PrintWriter writer = out.newPrintWriter()
            unifiedDiff.each { line -> writer.write(line) }
            writer.close()
        }

        patch.getDeltas().size()
    }

    static IChangelistSummary lastCommitForPath(IServer p4Server, String path) {
        List<IFileSpec> pathSpec    = new ArrayList()
        if (path != null) {
            pathSpec = FileSpecBuilder.makeFileSpecList(path)
        }
        List<IChangelistSummary> changes = p4Server.getChangelists(1,
                pathSpec, p4Server.getCurrentClient().getName(), null,
                true, IChangelist.Type.SUBMITTED, true)
        return changes.isEmpty() ? null : changes.get(0)
    }

    static List<IFileSpec> listChanges(IServer p4Server, int oldCommit) {
        String[] args = ["//...@${oldCommit},#${IFileSpec.HEAD_REVISION_STRING}"]
        List<Map<String, Object>> resultMaps = ((Server)p4Server).execMapCmdList(CmdSpec.FILELOG, args, null)
        return resultMaps.findAll { it["change0"] > oldCommit }
    }

    static Map<String, Serializable> metaForCommit(IChangelistSummary change, IServer server) {
        IUser user  = server.getUser(change.getUsername())
        [
                commitId      : change.getId(),
                date          : change.getDate(),
                authorName    : user.getFullName(),
                authorEmail   : user.getEmail(),
                message       : change.getDescription()
        ]
    }

    static ILabel createLabel(IServer p4Server, String labelName, String message,
                           String depotPath, IChangelistSummary commit) {
        Date lastAccess = null
        Date lastUpdate = null
        ViewMap<ILabelMapping> labelView = new ViewMap<>()
        ILabelMapping mapping = new Label.LabelMapping();
        mapping.setLeft(depotPath);
        labelView.addEntry(mapping);
        Label label = new Label(labelName, p4Server.getUserName(), lastAccess, lastUpdate,
                message, "@" + commit.getId(), true, labelView)
        return p4Server.createLabel(label)
    }
}
