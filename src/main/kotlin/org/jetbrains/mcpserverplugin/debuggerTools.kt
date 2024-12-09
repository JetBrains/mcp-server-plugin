package org.jetbrains.mcpserverplugin

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import com.intellij.xdebugger.impl.XSourcePositionImpl
import com.intellij.xdebugger.impl.breakpoints.XBreakpointUtil
import org.jetbrains.ide.mcp.McpTool
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import kotlin.reflect.KClass

data class ToggleBreakpointArgs(val filePathInProject: String, val line: Int)
class ToggleBreakpointTool : McpTool<ToggleBreakpointArgs> {
    override val name: String = "toggle_debugger_breakpoint"
    override val description: String = "Toggle debugger breakpoint at specified location"
    override val argKlass: KClass<*> = ToggleBreakpointArgs::class

    override fun handle(
        project: Project,
        args: ToggleBreakpointArgs
    ): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response("can't find project dir")
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(projectDir.resolve(args.filePathInProject))

        runWriteAction {
            val position = XSourcePositionImpl.create(virtualFile, args.line)
            val breakpointType = XBreakpointUtil.toggleLineBreakpoint(project, position, false, null, false, true, true)
        }

        return Response("ok")
    }
}

class GetBreakpointsTool : McpTool<NoArgs> {
    override val name: String = "get_debugger_breakpoints"
    override val description: String = "Get list of all debugger breakpoints in the project"
    override val argKlass: KClass<*> = NoArgs::class

    override fun handle(
        project: Project,
        args: NoArgs
    ): Response {
        TODO("Not yet implemented")
    }
}