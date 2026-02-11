package com.openclaw.visioncontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val icon: String = "📋"
)

object SkillManager {
    
    private const val PREFS_CUSTOM_SKILLS = "custom_skills"
    
    // Built-in skills
    private val builtInSkills = listOf(
        Skill(
            id = "general",
            name = "General (No Skill)",
            description = "No specific instructions, just complete the task",
            instructions = "",
            icon = "🎯"
        ),
        Skill(
            id = "gmail",
            name = "Gmail",
            description = "Composing and managing emails in Gmail",
            instructions = """
                |You are operating Gmail in a web browser.
                |
                |KEY UI ELEMENTS:
                |- Compose button: Red button, usually bottom-left or top-left
                |- Inbox: Left sidebar, shows unread count
                |- Search: Top center search bar
                |- Compose window: Floating dialog with To, Subject, Body fields
                |- Send button: Blue button, bottom-left of compose window
                |
                |KEYBOARD SHORTCUTS (Gmail has these enabled):
                |- c = Compose new email
                |- / = Focus search
                |- e = Archive selected
                |- # = Delete
                |- r = Reply
                |- a = Reply all
                |- f = Forward
                |- Ctrl+Enter = Send email
                |
                |TIPS:
                |- Tab moves between To → Subject → Body in compose
                |- Use keyboard shortcuts when possible, they're faster
                |- Wait for compose window animation to finish before typing
            """.trimMargin(),
            icon = "📧"
        ),
        Skill(
            id = "excel",
            name = "Microsoft Excel",
            description = "Spreadsheet navigation and data entry",
            instructions = """
                |You are operating Microsoft Excel.
                |
                |KEY UI ELEMENTS:
                |- Cell grid: Main area, click cells to select
                |- Formula bar: Shows cell contents, top of screen
                |- Sheet tabs: Bottom of screen
                |- Ribbon: Top toolbar with tabs (Home, Insert, etc.)
                |
                |KEYBOARD SHORTCUTS:
                |- Ctrl+C/V/X = Copy/Paste/Cut
                |- Ctrl+Z = Undo
                |- Ctrl+S = Save
                |- Ctrl+Home = Go to cell A1
                |- Ctrl+End = Go to last used cell
                |- Ctrl+Arrow = Jump to edge of data
                |- F2 = Edit cell
                |- Tab = Move right one cell
                |- Enter = Move down one cell
                |- Ctrl+; = Insert current date
                |- Alt+= = AutoSum
                |
                |TIPS:
                |- Type directly to replace cell contents
                |- Press F2 to edit existing cell contents
                |- Use arrow keys to navigate between cells
                |- Double-click column border to auto-fit width
            """.trimMargin(),
            icon = "📊"
        ),
        Skill(
            id = "web_form",
            name = "Web Form Filling",
            description = "Filling out forms on websites",
            instructions = """
                |You are filling out a web form.
                |
                |NAVIGATION:
                |- Tab = Move to next field
                |- Shift+Tab = Move to previous field
                |- Space = Toggle checkboxes, activate buttons
                |- Enter = Submit form (when on submit button)
                |- Arrow keys = Navigate dropdown options
                |
                |FIELD TYPES:
                |- Text input: Click and type
                |- Dropdown: Click to open, then click option or use arrows
                |- Checkbox: Click to toggle
                |- Radio buttons: Click the option you want
                |- Date picker: May need to click calendar icon
                |
                |TIPS:
                |- Look for red asterisks (*) indicating required fields
                |- Check for validation errors after filling (usually red text)
                |- Some forms have multiple pages/steps
                |- Look for "Next", "Continue", or "Submit" buttons
                |- Prefer Tab navigation over clicking each field
            """.trimMargin(),
            icon = "📝"
        ),
        Skill(
            id = "terminal",
            name = "Terminal / Command Line",
            description = "Operating command line interfaces",
            instructions = """
                |You are operating a terminal/command line.
                |
                |INTERFACE:
                |- Prompt shows current directory and awaits input
                |- Output appears below commands
                |- Scroll up to see previous output
                |
                |KEY SHORTCUTS:
                |- Ctrl+C = Cancel current command
                |- Ctrl+L = Clear screen
                |- Ctrl+A = Go to start of line
                |- Ctrl+E = Go to end of line
                |- Up/Down arrows = Previous/next command history
                |- Tab = Autocomplete file/directory names
                |
                |TIPS:
                |- Wait for command to complete before typing next
                |- Look for the prompt to know when ready
                |- If something seems stuck, try Ctrl+C
                |- Use 'ls' or 'dir' to see files
                |- Use 'cd' to change directories
                |- Read error messages carefully
            """.trimMargin(),
            icon = "💻"
        ),
        Skill(
            id = "slack",
            name = "Slack",
            description = "Messaging and channels in Slack",
            instructions = """
                |You are operating Slack.
                |
                |KEY UI ELEMENTS:
                |- Sidebar: Channels and DMs on the left
                |- Message input: Bottom of screen
                |- Channel header: Top shows channel name and info
                |- Thread panel: Opens on the right side
                |
                |KEYBOARD SHORTCUTS:
                |- Ctrl+K or Cmd+K = Quick switcher (search channels/people)
                |- Ctrl+/ = Show all shortcuts
                |- Up arrow = Edit last message
                |- Shift+Enter = New line without sending
                |- Enter = Send message
                |- Ctrl+Shift+\ = React to last message
                |- Ctrl+U = Upload file
                |
                |TIPS:
                |- Use @username to mention someone
                |- Use #channel to link a channel
                |- Ctrl+K is the fastest way to navigate
                |- Click "Reply in thread" to keep conversations organized
                |- Look for the green dot for online status
            """.trimMargin(),
            icon = "💬"
        )
    )
    
    fun getAllSkills(context: Context): List<Skill> {
        val customSkills = getCustomSkills(context)
        return builtInSkills + customSkills
    }
    
    fun getSkillById(context: Context, id: String): Skill? {
        return getAllSkills(context).find { it.id == id }
    }
    
    fun getCustomSkills(context: Context): List<Skill> {
        val prefs = context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(PREFS_CUSTOM_SKILLS, "[]") ?: "[]"
        
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Skill(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    description = obj.optString("description", ""),
                    instructions = obj.getString("instructions"),
                    icon = obj.optString("icon", "📋")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun saveCustomSkill(context: Context, skill: Skill) {
        val prefs = context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val existing = getCustomSkills(context).toMutableList()
        
        // Remove if exists (for updates)
        existing.removeAll { it.id == skill.id }
        existing.add(skill)
        
        val array = JSONArray()
        existing.forEach { s ->
            val obj = JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("description", s.description)
                put("instructions", s.instructions)
                put("icon", s.icon)
            }
            array.put(obj)
        }
        
        prefs.edit().putString(PREFS_CUSTOM_SKILLS, array.toString()).apply()
    }
    
    fun deleteCustomSkill(context: Context, skillId: String) {
        val prefs = context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val existing = getCustomSkills(context).filter { it.id != skillId }
        
        val array = JSONArray()
        existing.forEach { s ->
            val obj = JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("description", s.description)
                put("instructions", s.instructions)
                put("icon", s.icon)
            }
            array.put(obj)
        }
        
        prefs.edit().putString(PREFS_CUSTOM_SKILLS, array.toString()).apply()
    }
}
