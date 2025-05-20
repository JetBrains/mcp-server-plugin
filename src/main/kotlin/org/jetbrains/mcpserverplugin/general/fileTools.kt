package org.jetbrains.mcpserverplugin.general

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManager.getInstance
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.io.createParentDirectories
import kotlinx.serialization.Serializable
import org.jetbrains.ide.RestService.Companion.getLastFocusedOrOpenedProject
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.ProjectAware
import org.jetbrains.ide.mcp.ProjectOnly
import org.jetbrains.ide.mcp.Response
import org.jetbrains.ide.mcp.getProject
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import java.nio.file.Path
import kotlin.io.path.*


@Serializable
data class ListDirectoryTreeInFolderArgs(
    override val projectName: String,
    val pathInProject: String,
    val maxDepth: Int = 5,
) : ProjectAware

class ListDirectoryTreeInFolderTool : AbstractMcpTool<ListDirectoryTreeInFolderArgs>(ListDirectoryTreeInFolderArgs.serializer()) {
    override val name: String = "list_directory_tree_in_folder"
    override val description: String = """
        Provides a hierarchical tree view of the project directory structure starting from the specified folder.
        Use this tool to efficiently explore complex project structures in a nested format.
        Requires three parameters:
        - pathInProject: Path to the folder to list (use "/" for project root)
        - maxDepth: Maximum recursion depth (default: 5)
        - projectName: The name of the project to explore. Use list_projects tool to get available project names.
        Returns a JSON-formatted tree structure, where each entry contains:
        - name: The name of the file or directory
        - type: Either "file" or "directory"
        - path: Full path relative to project root
        - children: Array of child entries (for directories only)
        Returns error if the specified path doesn't exist or is outside project scope.
    """.trimIndent()

    override fun handle(args: ListDirectoryTreeInFolderArgs): Response {
        val project = args.getProject() ?: return Response(error = "Project not found")
        val projectDir = args.getProject()?.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")

        return runReadAction {
            try {
                val targetDir = projectDir.resolveRel(args.pathInProject)

                if (!targetDir.exists()) {
                    return@runReadAction Response(error = "directory not found")
                }

                val entryTree = buildDirectoryTree(projectDir, targetDir, args.maxDepth)
                val jsonTree = entryTreeToJson(entryTree)
                Response(jsonTree)
            } catch (e: Exception) {
                Response(error = "Error creating directory tree: ${e.message}")
            }
        }
    }

    private data class Entry(
        val name: String,
        val type: String,
        val path: String,
        val children: MutableList<Entry> = mutableListOf()
    )

    private fun buildDirectoryTree(projectDir: Path, current: Path, maxDepth: Int, currentDepth: Int = 0): Entry {
        val relativePath = projectDir.relativize(current).toString()
        val type = if (current.isDirectory()) "directory" else "file"
        val entry = Entry(name = current.name, type = type, path = relativePath)
        if (current.isDirectory()) {
            if (currentDepth >= maxDepth) return entry
            current.listDirectoryEntries().forEach { childPath ->
                entry.children.add(buildDirectoryTree(projectDir, childPath, maxDepth, currentDepth + 1))
            }
        }
        return entry
    }

    private fun entryTreeToJson(entry: Entry): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"name\":\"${entry.name}\",")
        sb.append("\"type\":\"${entry.type}\",")
        sb.append("\"path\":\"${entry.path}\"")
        if (entry.type == "directory") {
            sb.append(",\"children\":[")
            entry.children.forEachIndexed { index, child ->
                if (index > 0) sb.append(",")
                sb.append(entryTreeToJson(child))
            }
            sb.append("]")
        }
        sb.append("}")
        return sb.toString()
    }
}


@Serializable
data class ListFilesInFolderArgs(val pathInProject: String, override val projectName: String) : ProjectAware

class ListFilesInFolderTool : AbstractMcpTool<ListFilesInFolderArgs>(ListFilesInFolderArgs.serializer()) {
    override val name: String = "list_files_in_folder"
    override val description: String = """
        Lists all files and directories in the specified project folder.
        Use this tool to explore project structure and get contents of any directory.
        Requires two parameters:
        - pathInProject: Path to the folder to list (use "/" for project root)
        - projectName: The name of the project to explore. Use list_projects tool to get available project names.
        Returns a JSON-formatted list of entries, where each entry contains:
        - name: The name of the file or directory
        - type: Either "file" or "directory"
        - path: Full path relative to project root
        Returns error if the specified path doesn't exist or is outside project scope.
    """.trimIndent()

    override fun handle(args: ListFilesInFolderArgs): Response {
        val projectDir = args.getProject()?.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")

        return runReadAction {
            try {
                val targetDir = projectDir.resolveRel(args.pathInProject)

                if (!targetDir.exists()) {
                    return@runReadAction Response(error = "directory not found")
                }

                val entries = targetDir.listDirectoryEntries().map { entry ->
                    val type = if (entry.isDirectory()) "directory" else "file"
                    val relativePath = projectDir.relativize(entry).toString()
                    """{"name": "${entry.name}", "type": "$type", "path": "$relativePath"}"""
                }

                Response(entries.joinToString(",\n", prefix = "[", postfix = "]"))
            } catch (e: Exception) {
                Response(error = "Error listing directory: ${e.message}")
            }
        }
    }
}


@Serializable
data class Query(val nameSubstring: String, override val projectName: String) : ProjectAware

class FindFilesByNameSubstring : AbstractMcpTool<Query>(Query.serializer()) {
    override val name: String = "find_files_by_name_substring"
    override val description: String = """
        Searches for all files in the project whose names contain the specified substring (case-insensitive).
        Use this tool to locate files when you know part of the filename.
        Requires two parameters:
        - nameSubstring: The search term to find in filenames
        - projectName: The name of the project to search in. Use list_projects tool to get available project names.
        Returns a JSON array of objects containing file information:
        - path: Path relative to project root
        - name: File name
        Returns an empty array ([]) if no matching files are found.
        Note: Only searches through files within the project directory, excluding libraries and external dependencies.
    """

    override fun handle(args: Query): Response {
        val project = args.getProject() ?: return Response(error = "Project not found")
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "project dir not found")

        val searchSubstring = args.nameSubstring.toLowerCase()
        return runReadAction {
            Response(
                FilenameIndex.getAllFilenames(project)
                    .filter { it.toLowerCase().contains(searchSubstring) }
                    .flatMap {
                        FilenameIndex.getVirtualFilesByName(it, GlobalSearchScope.projectScope(project))
                    }
                    .filter { file ->
                        try {
                            projectDir.relativize(Path(file.path))
                            true
                        } catch (e: IllegalArgumentException) {
                            false
                        }
                    }
                    .map { file ->
                        val relativePath = projectDir.relativize(Path(file.path)).toString()
                        """{"path": "$relativePath", "name": "${file.name}"}"""
                    }
                    .joinToString(",\n", prefix = "[", postfix = "]")
            )
        }
    }
}


@Serializable
data class CreateNewFileWithTextArgs(val pathInProject: String, val text: String, override val projectName: String) : ProjectAware

class CreateNewFileWithTextTool : AbstractMcpTool<CreateNewFileWithTextArgs>(CreateNewFileWithTextArgs.serializer()) {
    override val name: String = "create_new_file_with_text"
    override val description: String = """
        Creates a new file at the specified path within the project directory and populates it with the provided text.
        Use this tool to generate new files in your project structure.
        Requires three parameters:
        - pathInProject: The relative path where the file should be created
        - text: The content to write into the new file
        - projectName: The name of the project to create file in. Use list_projects tool to get available project names.
        Returns one of two possible responses:
        - "ok" if the file was successfully created and populated
        - "can't find project dir" if the project directory cannot be determined
        Note: Creates any necessary parent directories automatically
    """

    override fun handle(args: CreateNewFileWithTextArgs): Response {
        val project = args.getProject() ?: return Response(error = "Project not found")
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")

        val path = projectDir.resolveRel(args.pathInProject)
        if (!path.exists()) {
            path.createParentDirectories().createFile()
        }
        val text = args.text
        path.writeText(text.unescape())
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)

        return Response("ok")
    }

    private fun String.unescape(): String = removePrefix("<![CDATA[").removeSuffix("]]>")
}


@Serializable
data class OpenFileInEditorArgs(val filePath: String, override val projectName: String) : ProjectAware

class OpenFileInEditorTool : AbstractMcpTool<OpenFileInEditorArgs>(OpenFileInEditorArgs.serializer()) {
    override val name: String = "open_file_in_editor"
    override val description: String = """
        Opens the specified file in the JetBrains IDE editor.
        Requires two parameters:
        - filePath: The path of file to open (can be absolute or relative to the project root)
        - projectName: The name of the project containing the file. Use list_projects tool to get available project names.
        Returns one of two possible responses:
        - "file is opened" if the file was successfully opened
        - "file doesn't exist or can't be opened" otherwise
    """.trimIndent()

    override fun handle(args: OpenFileInEditorArgs): Response {
        val project = args.getProject() ?: return Response(error = "Project not found")
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "can't find project dir")

        val file = LocalFileSystem.getInstance().findFileByPath(args.filePath)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectDir.resolveRel(args.filePath))

        return if (file != null && file.exists()) {
            invokeLater {
                FileEditorManager.getInstance(project).openFile(file, true)
            }
            Response("file is opened")
        } else {
            Response("file doesn't exist or can't be opened")
        }
    }
}


class GetAllOpenFilePathsTool : AbstractMcpTool<ProjectOnly>(ProjectOnly.serializer()) {
    override val name: String = "get_all_open_file_paths"
    override val description: String = """
        Lists full path relative paths to project root of all currently open files in the JetBrains IDE editor.
        Returns a list of file paths that are currently open in editor tabs.
        Requires one parameter:
        - projectName: The name of the project to get open file paths from. Use list_projects tool to get available project names.
        Returns an empty list if no files are open.

        Use this tool to explore current open editors.
        Returns a list of file paths separated by newline symbol.
    """.trimIndent()

    override fun handle(args: ProjectOnly): Response {
        val project = args.getProject() ?: return Response(error = "Project not found")
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()

        val fileEditorManager = FileEditorManager.getInstance(project)
        val openFiles = fileEditorManager.openFiles
        val filePaths = openFiles.mapNotNull { it.toNioPath().relativizeByProjectDir(projectDir) }
        return Response(filePaths.joinToString("\n"))
    }
}


class GetCurrentFilePathTool : AbstractMcpTool<NoArgs>(NoArgs.serializer()) {
    override val name: String = "get_open_in_editor_file_path"
    override val description: String = """
        Retrieves the absolute path of the currently active file in the JetBrains IDE editor.
        Use this tool to get the file location for tasks requiring file path information.
        Returns an empty string if no file is currently open.
    """.trimIndent()

    override fun handle(args: NoArgs): Response {
        val project = getLastFocusedOrOpenedProject() ?: return Response(error = "Project not found")
        val path = runReadAction<String?> {
            getInstance(project).selectedTextEditor?.virtualFile?.path
        }
        return Response(path ?: "")
    }
}