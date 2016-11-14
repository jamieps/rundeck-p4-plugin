package org.rundeck.plugin.scm.p4.imp.actions

import com.dtolabs.rundeck.core.plugins.configuration.StringRenderingConstants
import com.dtolabs.rundeck.core.plugins.views.BasicInputView
import com.dtolabs.rundeck.plugins.scm.JobImporter
import com.dtolabs.rundeck.plugins.scm.ScmExportResult
import com.dtolabs.rundeck.plugins.scm.ScmExportResultImpl
import com.dtolabs.rundeck.plugins.scm.ScmOperationContext
import org.rundeck.plugin.scm.p4.BaseAction
import org.rundeck.plugin.scm.p4.P4ImportAction
import org.rundeck.plugin.scm.p4.P4ImportPlugin

import static org.rundeck.plugin.scm.p4.BuilderUtil.inputView
import static org.rundeck.plugin.scm.p4.BuilderUtil.property

/**
 * Created by greg on 9/8/15.
 */
class SyncAction extends BaseAction implements P4ImportAction {
    SyncAction(final String id, final String title, final String description) {
        super(id, title, description)
    }

    @Override
    BasicInputView getInputView(final ScmOperationContext context, final P4ImportPlugin plugin) {
        inputView(id) {
            title "Sync remote changes"
            buttonTitle "Sync"
            properties([
                    property {
                        string "status"
                        title "Perforce Status"
                        renderingOption StringRenderingConstants.DISPLAY_TYPE_KEY, StringRenderingConstants.DisplayType.STATIC_TEXT
                        renderingOption StringRenderingConstants.STATIC_TEXT_CONTENT_TYPE_KEY, "text/x-markdown"
                        defaultValue "Syncing from depot path: `${plugin.depotPath}`"
                    },
            ]
            )
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
        //fetch remote changes
        def update = plugin.fetchFromRemote(context)

        def result = new ScmExportResultImpl()
        result.success = true
        result.message = update ? update.toString() : "No changes were found"
        result
    }
}
