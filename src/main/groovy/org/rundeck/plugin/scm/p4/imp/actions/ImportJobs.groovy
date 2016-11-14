package org.rundeck.plugin.scm.p4.imp.actions

import com.dtolabs.rundeck.core.plugins.views.BasicInputView
import com.dtolabs.rundeck.plugins.scm.JobImporter
import com.dtolabs.rundeck.plugins.scm.ScmExportResult
import com.dtolabs.rundeck.plugins.scm.ScmExportResultImpl
import com.dtolabs.rundeck.plugins.scm.ScmOperationContext
import com.perforce.p4java.core.file.IFileSpec
import com.perforce.p4java.option.server.GetFileContentsOptions
import org.rundeck.plugin.scm.p4.*

/**
 * Action to import selected jobs from p4 HEAD commit
 */
class ImportJobs extends BaseAction implements P4ImportAction {
    ImportJobs(final String id, final String title, final String description, final String iconName) {
        super(id, title, description, iconName)
    }


    BasicInputView getInputView(final ScmOperationContext context, P4ImportPlugin plugin) {
        BuilderUtil.inputViewBuilder(id) {
            title "Import remote Changes"
            description '''Import the modifications to Rundeck'''
            buttonTitle "Import"
            properties([])
        }
    }

    @Override
    ScmExportResult performAction(
            final ScmOperationContext context,
            final P4ImportPlugin plugin,
            final JobImporter importer,
            final List<String> selectedPaths,
            final Map<String, String> input
    )
    {
        //perform p4
        StringBuilder sb = new StringBuilder()
        boolean success = true

        // Walk the repo files and look for possible candidates
        plugin.p4Client.haveList(null).each {
            IFileSpec fileSpec ->
                def path    = fileSpec.getLocalPathString()
                if (!(path in selectedPaths)) {
                    plugin.log.debug("not selected, skipping path ${path}")
                    return
                }
                InputStream inputStr = fileSpec.getContents(new GetFileContentsOptions().setNoHeaderLine(true))
                ByteArrayOutputStream buffer = new ByteArrayOutputStream()
                byte[] sink = new byte[4096]
                int bytesRead
                while ((bytesRead = inputStr.read(sink)) != -1) {
                    buffer.write(sink, 0, bytesRead)
                }
                def size = buffer.size()
                plugin.log.debug("import data: ${size} = ${path}")
                def bytes = buffer.toByteArray()

                def commit = P4Util.lastCommitForPath(plugin.p4Server, path)
                def meta = P4Util.metaForCommit(commit, plugin.p4Server)

                def importResult = importer.importFromStream(
                        plugin.config.format,
                        new ByteArrayInputStream(bytes),
                        meta
                )
                if (!importResult.successful) {
                    success = false
                    sb << ("Failed importing: ${path}: " + importResult.errorMessage)
                } else {
                    plugin.importTracker.trackJobAtPath(importResult.job, path)
                    sb << ("Succeeded importing ${path}: ${importResult}")
                }
        }

        def result = new ScmExportResultImpl()
        result.success = success
        result.message = "Perforce Import " + (success ? "successful" : "failed")
        result.extendedMessage = sb.toString()
        return result

    }

}