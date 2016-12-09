package org.rundeck.plugin.scm.p4.exp.actions

import com.dtolabs.rundeck.core.plugins.configuration.StringRenderingConstants
import com.dtolabs.rundeck.core.plugins.views.BasicInputView
import com.dtolabs.rundeck.plugins.scm.*
import com.perforce.p4java.core.file.FileSpecBuilder
import com.perforce.p4java.core.file.FileSpecOpStatus
import com.perforce.p4java.core.file.IFileSpec
import com.perforce.p4java.option.client.SyncOptions
import org.rundeck.plugin.scm.p4.BaseAction
import org.rundeck.plugin.scm.p4.BuilderUtil
import org.rundeck.plugin.scm.p4.P4ExportAction
import org.rundeck.plugin.scm.p4.P4ExportPlugin

/**
 * Created by greg on 9/8/15.
 */
class SynchAction extends BaseAction implements P4ExportAction {
    static final enum MergeOptions {
        ACCEPT_YOURS("yours"),
        ACCEPT_THEIRS("theirs"),
        MERGE("merge")

        static MergeOptions valueOfName(String name) {
            values().find { it.name == name }
        }
    }

    SynchAction(final String id, final String title, final String description) {
        super(id, title, description)
    }

    @Override
    BasicInputView getInputView(final ScmOperationContext context, P4ExportPlugin plugin) {
        def status = plugin.getStatusInternal(context, false)
        def props = [
                BuilderUtil.property {
                    string "status"
                    title "Perforce Status"
                    renderingOption StringRenderingConstants.DISPLAY_TYPE_KEY, StringRenderingConstants.DisplayType.STATIC_TEXT
                    renderingOption StringRenderingConstants.STATIC_TEXT_CONTENT_TYPE_KEY, "text/x-markdown"
                    defaultValue status.message
                },
        ]
        if (status.p4Status.find { it.resolveType == "unresolved" }) {
            props.addAll([
                    BuilderUtil.property {
                        select "resolution"
                        title "Conflict Resolution Strategy"
                        description """Choose a strategy to resolve conflicts in the synched files.

* `ours` - apply our changes over theirs
* `theirs` - apply their changes over ours
* `merge` - automatic merge"""
                        values(["yours", "theirs", "merge"])
                        defaultValue "yours"
                        required true
                    },
            ]
            )
        }
        BuilderUtil.inputViewBuilder(id) {
            title this.title
            description this.description
            buttonTitle "Synch"
            properties props
        }
    }

    @Override
    ScmExportResult perform(
            final P4ExportPlugin plugin,
            final Set<JobExportReference> jobs,
            final Set<String> pathsToDelete,
            final ScmOperationContext context,
            final Map<String, String> input
    ) throws ScmPluginException
    {
        def status = plugin.getStatusInternal(context, false)

        List<IFileSpec> syncFiles = plugin.p4Client.sync(FileSpecBuilder
                .makeFileSpecList("//${plugin.p4Client.getName()}/..."), new SyncOptions())
        syncFiles.each { IFileSpec fileSpec ->
            if (fileSpec?.getOpStatus() == FileSpecOpStatus.VALID) {
                plugin.logger.debug("SynchAction: sync: ${fileSpec.getDepotPath()}")
            } else {
                plugin.logger.error("SynchAction: ${fileSpec?.getStatusMessage()}")
            }
        }

        // TODO: Do a p4 resolve -am or -as here, if there are conflicts
        if (status.p4Status.find { it.resolveType == "unresolved" }) {
            plugin.logger.debug("SynchAction: attempting conflict resolution")
            plugin.p4Resolve(context)
        } else {
            // No resolve needed.
            plugin.logger.debug("SynchAction: no conflicts found.")
        }
    }

    ScmExportResult gitPull(final ScmOperationContext context, final P4ExportPlugin plugin) {
        def pullResult = plugin.gitPull(context)
        def result = new ScmExportResultImpl()
        result.success = pullResult.successful
        result.message = "Git Pull " + (result.success ? 'succeeded' : 'failed')
        result.extendedMessage = pullResult.mergeResult?.toString() ?: null
        result
    }
}
