package app.termora.sftp

import app.termora.*
import app.termora.actions.DataProvider
import app.termora.actions.DataProviderSupport
import app.termora.findeverywhere.FindEverywhereProvider
import app.termora.terminal.DataKey
import okio.withLock
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.SystemUtils
import org.apache.sshd.sftp.client.fs.SftpFileSystem
import java.awt.BorderLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Path
import javax.swing.*
import kotlin.io.path.absolutePathString

fun FileSystem.isSFTP() = this is SftpFileSystem
fun FileSystem.isUnix() = isLocal() && SystemUtils.IS_OS_UNIX
fun FileSystem.isWindows() = isLocal() && SystemUtils.IS_OS_WINDOWS
fun FileSystem.isLocal() = StringUtils.startsWithAny(javaClass.name, "java", "sun")

class SFTPPanel : JPanel(BorderLayout()), DataProvider, Disposable {

    private val transportTable = TransportTable()
    private val transportManager get() = transportTable.model
    private val dataProviderSupport = DataProviderSupport()
    private val leftComponent = SFTPTabbed(transportManager)
    private val rightComponent = SFTPTabbed(transportManager)

    init {
        initViews()
        initEvents()
        FileSystems.getDefault()
    }

    private fun initViews() {

        putClientProperty(FindEverywhereProvider.SKIP_FIND_EVERYWHERE, true)

        val splitPane = JSplitPane()
        splitPane.resizeWeight = 0.5
        splitPane.leftComponent = leftComponent
        splitPane.rightComponent = rightComponent
        splitPane.border = BorderFactory.createMatteBorder(0, 0, 1, 0, DynamicColor.BorderColor)
        splitPane.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                removeComponentListener(this)
                splitPane.setDividerLocation(splitPane.resizeWeight)
            }
        })

        leftComponent.border = BorderFactory.createMatteBorder(0, 0, 0, 1, DynamicColor.BorderColor)
        rightComponent.border = BorderFactory.createMatteBorder(0, 1, 0, 0, DynamicColor.BorderColor)

        val rootSplitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        val scrollPane = JScrollPane(transportTable)
        scrollPane.border = BorderFactory.createMatteBorder(1, 0, 0, 0, DynamicColor.BorderColor)

        rootSplitPane.resizeWeight = 0.7
        rootSplitPane.topComponent = splitPane
        rootSplitPane.bottomComponent = scrollPane
        rootSplitPane.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                removeComponentListener(this)
                rootSplitPane.setDividerLocation(rootSplitPane.resizeWeight)
            }
        })

        add(rootSplitPane, BorderLayout.CENTER)
    }

    private fun initEvents() {
        Disposer.register(this, leftComponent)
        Disposer.register(this, rightComponent)
        Disposer.register(this, transportTable)

        dataProviderSupport.addData(SFTPDataProviders.TransportManager, transportManager)
        dataProviderSupport.addData(SFTPDataProviders.LeftSFTPTabbed, leftComponent)
        dataProviderSupport.addData(SFTPDataProviders.RightSFTPTabbed, rightComponent)


        // default tab
        leftComponent.addTab(
            I18n.getString("termora.transport.local"),
            FileSystemViewPanel(
                Host(
                    id = "local",
                    name = I18n.getString("termora.transport.local"),
                    protocol = Protocol.Local,
                ), FileSystems.getDefault(), transportManager
            )
        )
        leftComponent.setTabClosable(0, false)


        // default tab
        rightComponent.addTab(
            I18n.getString("termora.transport.sftp.select-host"),
            SFTPFileSystemViewPanel(transportManager = transportManager)
        )

        rightComponent.addChangeListener {
            if (rightComponent.tabCount == 0 && !rightComponent.isDisposed) {
                rightComponent.addTab(
                    I18n.getString("termora.transport.sftp.select-host"),
                    SFTPFileSystemViewPanel(transportManager = transportManager)
                )
            }
        }

        leftComponent.setTabCloseCallback { _, index -> tabCloseCallback(leftComponent, index) }
        rightComponent.setTabCloseCallback { _, index -> tabCloseCallback(rightComponent, index) }
    }

    private fun tabCloseCallback(tabbed: SFTPTabbed, index: Int) {
        assertEventDispatchThread()

        val c = tabbed.getFileSystemViewPanel(index)
        if (c == null) {
            tabbed.removeTabAt(index)
            return
        }

        val fs = c.fileSystem
        val root = transportManager.root

        transportManager.lock.withLock {
            val deletedIds = mutableListOf<Long>()
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i) as? TransportTreeTableNode ?: continue
                if (child.transport.source.fileSystem == fs ||
                    child.transport.target.fileSystem == fs
                ) {
                    deletedIds.add(child.transport.id)
                }
            }

            if (deletedIds.isNotEmpty()) {
                if (OptionPane.showConfirmDialog(
                        SwingUtilities.getWindowAncestor(this),
                        I18n.getString("termora.transport.sftp.close-tab"),
                        messageType = JOptionPane.QUESTION_MESSAGE,
                        optionType = JOptionPane.OK_CANCEL_OPTION
                    ) != JOptionPane.OK_OPTION
                ) {
                    return
                }
            }

            deletedIds.forEach { transportManager.removeTransport(it) }
        }


        // 删除并销毁
        tabbed.removeTabAt(index)

    }

    /**
     * 返回失败表示没有创建成功
     */
    fun addTransport(
        source: JComponent,
        sourceWorkdir: Path?,
        target: FileSystemViewPanel,
        targetWorkdir: Path?,
        transport: Transport
    ): Boolean {

        val sourcePanel = SwingUtilities.getAncestorOfClass(FileSystemViewPanel::class.java, source)
                as? FileSystemViewPanel ?: return false
        val targetPanel = target as? FileSystemViewPanel ?: return false
        if (sourcePanel.isDisposed || targetPanel.isDisposed) return false
        val myTargetWorkdir = (targetWorkdir ?: targetPanel.getWorkdir()).absolutePathString()
        val mySourceWorkdir = (sourceWorkdir ?: sourcePanel.getWorkdir()).absolutePathString()
        val targetFileSystem = targetPanel.fileSystem
        val sourcePath = transport.source.absolutePathString()

        transport.target = targetFileSystem.getPath(
            myTargetWorkdir,
            StringUtils.removeStart(sourcePath, mySourceWorkdir)
        )

        return transportManager.addTransport(transport)

    }

    fun canTransfer(source: JComponent): Boolean {
        return getTarget(source) != null
    }

    fun getTarget(source: JComponent): FileSystemViewPanel? {
        val sourceTabbed = SwingUtilities.getAncestorOfClass(SFTPTabbed::class.java, source)
                as? SFTPTabbed ?: return null
        val isLeft = sourceTabbed == leftComponent
        val targetTabbed = if (isLeft) rightComponent else leftComponent
        return targetTabbed.getSelectedFileSystemViewPanel()
    }

    /**
     * 获取本地文件系统面板
     */
    fun getLocalTarget(): FileSystemViewPanel {
        return leftComponent.getFileSystemViewPanel(0) as FileSystemViewPanel
    }

    override fun <T : Any> getData(dataKey: DataKey<T>): T? {
        return dataProviderSupport.getData(dataKey)
    }

}