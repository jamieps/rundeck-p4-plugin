package org.rundeck.plugin.scm.p4

import com.dtolabs.rundeck.plugins.scm.ScmExportSynchState
import com.dtolabs.rundeck.plugins.scm.SynchState
import com.perforce.p4java.core.file.IFileSpec

/**
 *
 */
class P4ExportSynchState implements ScmExportSynchState {
    SynchState state
    String message
    List<IFileSpec> p4Status
}
