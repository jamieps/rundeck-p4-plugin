package org.rundeck.plugin.scm.p4

import com.dtolabs.rundeck.core.plugins.views.Action
import com.dtolabs.rundeck.core.plugins.views.BasicInputView
import com.dtolabs.rundeck.plugins.scm.JobExportReference
import com.dtolabs.rundeck.plugins.scm.ScmExportResult
import com.dtolabs.rundeck.plugins.scm.ScmOperationContext
import com.dtolabs.rundeck.plugins.scm.ScmPluginException

/**
 * Created by greg on 9/8/15.
 */
interface P4ExportAction extends Action {

    BasicInputView getInputView(final ScmOperationContext context, final P4ExportPlugin plugin)

    ScmExportResult perform(
            final P4ExportPlugin plugin,
            final Set<JobExportReference> jobs,
            final Set<String> pathsToDelete,
            final ScmOperationContext context,
            final Map<String, String> input
    ) throws ScmPluginException
}
