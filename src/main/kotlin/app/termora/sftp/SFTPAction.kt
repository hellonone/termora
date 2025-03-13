package app.termora.sftp

import app.termora.HostManager
import app.termora.HostTerminalTab
import app.termora.Icons
import app.termora.Protocol
import app.termora.actions.AnAction
import app.termora.actions.AnActionEvent
import app.termora.actions.DataProviders
import org.apache.commons.lang3.StringUtils

class SFTPAction : AnAction("SFTP", Icons.folder) {
    private val hostManager get() = HostManager.getInstance()

    override fun actionPerformed(evt: AnActionEvent) {

        val terminalTabbedManager = evt.getData(DataProviders.TerminalTabbedManager) ?: return
        var sftpTab: SFTPTab? = null
        for (tab in terminalTabbedManager.getTerminalTabs()) {
            if (tab is SFTPTab) {
                sftpTab = tab
                break
            }
        }

        // 创建一个新的
        if (sftpTab == null) {
            sftpTab = SFTPTab()
            terminalTabbedManager.addTerminalTab(sftpTab, false)
        }

        var hostId = if (evt is SFTPActionEvent) evt.hostId else StringUtils.EMPTY

        // 如果不是特定事件，那么尝试获取选中的Tab，如果是一个 Host 并且是 SSH 协议那么直接打开
        if (hostId.isBlank()) {
            val tab = terminalTabbedManager.getSelectedTerminalTab()
            if (tab is HostTerminalTab) {
                if (tab.host.protocol == Protocol.SSH || tab.host.protocol == Protocol.SFTPPty) {
                    hostId = tab.host.id
                }
            }
        }

        terminalTabbedManager.setSelectedTerminalTab(sftpTab)

        if (hostId.isBlank()) return

        val tabbed = sftpTab.getData(SFTPDataProviders.RightSFTPTabbed) ?: return
        // 如果已经打开了 那么直接选中
        for (i in 0 until tabbed.tabCount) {
            val fileSystemViewPanel = tabbed.getFileSystemViewPanel(i) ?: continue
            if (fileSystemViewPanel.host.id == hostId) {
                tabbed.selectedIndex = i
                return
            }
        }

        val host = hostManager.getHost(hostId) ?: return
        tabbed.addSFTPFileSystemViewPanelTab(host)

    }
}