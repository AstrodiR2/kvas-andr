package com.dimonchik.kvas.onboarding

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.dimonchik.kvas.MainActivity
import org.json.JSONObject
import java.util.UUID

object TermuxBridge {
    const val TERMUX_PACKAGE = "com.termux"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val RESULT_PREFS = "termux_install_result"
    const val RESULT_BUNDLE = "result"
    const val RPC_PREFS = "termux_rpc_result"
    const val TERMUX_HOME = "/data/data/com.termux/files/home"

    fun setupCommand(packageName: String): String = """bash -c 'set -e
node -e "const [a,b]=process.versions.node.split(/[.]/).map(Number);process.exit(a>22||(a===22&&b>=19)?0:1)" 2>/dev/null || { pkg update -y && pkg upgrade -y && pkg install -y nodejs git; }
mkdir -p ~/.termux
{ grep -qx allow-external-apps=true ~/.termux/termux.properties 2>/dev/null || echo allow-external-apps=true >> ~/.termux/termux.properties; }
termux-reload-settings || true
echo "Квас: настройка завершена"
am start -a android.intent.action.VIEW -d kvas://setup-complete -n $packageName/.MainActivity >/dev/null 2>&1 || echo "Не удалось вернуться в Квас. Откройте приложение вручную."'"""

    fun openTermux(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE) ?: return false
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun openRunCommandSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun installPi(context: Context): Boolean {
        val commandIntent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", "npm install -g --ignore-scripts @earendil-works/pi-coding-agent@1.4.0"))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Установка агента Квас")
            putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "Установка Pi 1.4.0 в Termux")
        }
        val callback = Intent(context, TermuxResultReceiver::class.java).apply { action = "com.dimonchik.kvas.TERMUX_RESULT" }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(context, 81840, callback, flags)
        commandIntent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
        return runCatching { context.startService(commandIntent); true }.getOrDefault(false)
    }

    /**
     * Reads only one directory under Termux $HOME. Output is JSON from Node, and the
     * request callback is correlated by requestId. No arbitrary command is accepted here.
     */
    fun listDirectory(context: Context, directory: String, requestId: String = UUID.randomUUID().toString()): String? {
        val normalized = normalizeHomeDirectory(directory) ?: return null
        val pathArg = shellQuote(normalized)
        val script = """const fs=require("fs");const p=process.argv[1];try{console.log(JSON.stringify(fs.readdirSync(p,{withFileTypes:true}).map(x=>({name:x.name,dir:x.isDirectory()}))))}catch(e){console.error(e.message);process.exit(1)}"""
        val shellCommand = "node -e '${script}' $pathArg"
        val commandIntent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", shellCommand))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Просмотр папки")
            putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "Чтение списка файлов в выбранной папке")
        }
        val callback = Intent(context, TermuxResultReceiver::class.java).apply {
            action = "com.dimonchik.kvas.TERMUX_LIST_DIRECTORY"
            putExtra("requestId", requestId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(context, requestId.hashCode(), callback, flags)
        commandIntent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
        return if (runCatching { context.startService(commandIntent); true }.getOrDefault(false)) requestId else null
    }

    /**
     * Executes a fixed, audited Node dispatcher for workspace operations. Model-provided
     * values are JSON/base64 data, never shell syntax. Every filesystem path is canonicalized
     * and checked against the selected workspace root inside Termux before use.
     */
    fun runWorkspaceOperation(
        context: Context,
        root: String,
        operation: String,
        arguments: JSONObject,
        requestId: String = UUID.randomUUID().toString()
    ): String? {
        val normalizedRoot = normalizeHomeDirectory(root) ?: return null
        val payload = JSONObject(arguments.toString())
            .put("root", normalizedRoot)
            .put("operation", operation)
        val encoded = Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val script = WORKSPACE_DISPATCHER
        val command = "node -e '${script}' '$encoded'"
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", normalizedRoot)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Инструмент Кваса")
            putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "Работа только внутри выбранной папки")
        }
        val callback = Intent(context, TermuxResultReceiver::class.java).apply {
            action = "com.dimonchik.kvas.TERMUX_WORKSPACE_TOOL"
            putExtra("requestId", requestId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        intent.putExtra(
            "com.termux.RUN_COMMAND_PENDING_INTENT",
            PendingIntent.getBroadcast(context, requestId.hashCode(), callback, flags)
        )
        return if (runCatching { context.startService(intent); true }.getOrDefault(false)) requestId else null
    }

    private fun normalizeHomeDirectory(input: String): String? {
        val candidate = input.trim().replace('\\', '/').trimEnd('/').ifEmpty { TERMUX_HOME }
        if (candidate != TERMUX_HOME && !candidate.startsWith("$TERMUX_HOME/")) return null
        if (candidate.split('/').any { it == ".." || it == "." }) return null
        return candidate
    }

    /** Quote a single POSIX shell argument without interpreting user-controlled characters. */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private val WORKSPACE_DISPATCHER = """
const fs=require("fs"),path=require("path"),cp=require("child_process"),crypto=require("crypto");
const p=JSON.parse(Buffer.from(process.argv[1],"base64").toString("utf8"));
const root=fs.realpathSync(p.root);
function inside(x){return x===root||x.startsWith(root+path.sep)}
function target(rel,existing){if(typeof rel!=="string"||path.isAbsolute(rel))throw Error("Path must be relative to workspace");const x=path.resolve(root,rel||".");if(!inside(x))throw Error("Path escapes selected workspace");if(existing){const r=fs.realpathSync(x);if(!inside(r))throw Error("Symlink escapes selected workspace");return r}const parent=fs.realpathSync(path.dirname(x));if(!inside(parent))throw Error("Parent escapes selected workspace");if(fs.existsSync(x)){if(fs.lstatSync(x).isSymbolicLink())throw Error("Symlink target rejected");const r=fs.realpathSync(x);if(!inside(r))throw Error("Target escapes selected workspace");return r}return path.join(parent,path.basename(x))}
const taskFile="/data/data/com.termux/files/home/.kvas/background-tasks.json";
function readTasks(){try{return JSON.parse(fs.readFileSync(taskFile,"utf8"))}catch(e){return []}}
function writeTasks(rows){fs.mkdirSync(path.dirname(taskFile),{recursive:true});fs.writeFileSync(taskFile,JSON.stringify(rows),"utf8")}
function startTicks(pid){try{const s=fs.readFileSync("/proc/"+pid+"/stat","utf8"),rest=s.slice(s.lastIndexOf(")")+2).split(" ");return rest[19]||""}catch(e){return ""}}
function walk(dir,out){if(out.length>=3000)return;for(const e of fs.readdirSync(dir,{withFileTypes:true})){if([".git","node_modules",".gradle",".kotlin",".kvas"].includes(e.name))continue;const x=path.join(dir,e.name);if(e.isDirectory()){walk(x,out)}else if(e.isFile()){out.push(x);if(out.length>=3000)return}}}
try{let out;if(p.operation==="list_directory"){const d=target(p.path,true);out=fs.readdirSync(d,{withFileTypes:true}).map(e=>({name:e.name,directory:e.isDirectory()}))}
else if(p.operation==="read_file"){const f=target(p.path,true);if(fs.statSync(f).size>262144)throw Error("File exceeds 256 KB read limit");out={content:fs.readFileSync(f,"utf8")}}
else if(p.operation==="read_file_binary"){const f=target(p.path,true);if(!fs.statSync(f).isFile())throw Error("Only files can be sent");if(fs.statSync(f).size>1048576)throw Error("File exceeds 1 MB attachment limit");out={contentBase64:fs.readFileSync(f).toString("base64"),name:path.basename(f)}}
else if(p.operation==="write_file"){const f=target(p.path,false),parent=path.dirname(f);if(!inside(fs.realpathSync(parent)))throw Error("Parent escapes selected workspace");if(fs.existsSync(f))throw Error("File already exists; use edit_file for an existing file");if(Buffer.byteLength(p.content||"","utf8")>262144)throw Error("File exceeds 256 KB write limit");fs.writeFileSync(f,p.content||"","utf8");out={written:path.relative(root,f)}}
else if(p.operation==="create_directory"){const f=target(p.path,false);fs.mkdirSync(f);out={created:path.relative(root,f)}}
else if(p.operation==="edit_file"){const f=target(p.path,true);if(fs.statSync(f).size>262144)throw Error("File exceeds 256 KB edit limit");const before=fs.readFileSync(f,"utf8"),needle=p.old_text||"",count=needle?before.split(needle).length-1:0;if(count!==1)throw Error("Exact old_text must match exactly once");const after=before.replace(needle,p.new_text||"");fs.writeFileSync(f,after,"utf8");out={edited:path.relative(root,f),before:before,after:after}}
else if(p.operation==="delete_file"){const f=target(p.path,true);if(!fs.statSync(f).isFile())throw Error("Only files can be removed");const current=fs.readFileSync(f,"utf8");if(current!==(p.expected_content||""))throw Error("File changed since the last agent edit; refusing to delete it");fs.unlinkSync(f);out={deleted:path.relative(root,f)}}
else if(p.operation==="restore_file"){const f=target(p.path,true);if(!fs.statSync(f).isFile())throw Error("Only files can be restored");const current=fs.readFileSync(f,"utf8");if(current!==(p.expected_content||""))throw Error("File changed since the last agent edit; refusing to overwrite it");fs.writeFileSync(f,p.content||"","utf8");out={restored:path.relative(root,f)}}
else if(p.operation==="search_files"){const q=String(p.query||"").toLowerCase();if(!q)throw Error("Search query is empty");const all=[];walk(root,all);const hits=[];for(const f of all){if(path.basename(f).toLowerCase().includes(q))hits.push({path:path.relative(root,f),kind:"name"});if(hits.length>=100)break;try{if(fs.statSync(f).size<=131072){const s=fs.readFileSync(f,"utf8");const n=s.toLowerCase().indexOf(q);if(n>=0)hits.push({path:path.relative(root,f),kind:"content",excerpt:s.slice(Math.max(0,n-80),Math.min(s.length,n+160))})}}catch(e){}if(hits.length>=100)break}out={matches:hits}}
else if(p.operation==="bash"){const r=target(".",true),seconds=Math.max(1,Math.min(120,Number(p.timeout_seconds)||30));const x=cp.spawnSync("/data/data/com.termux/files/usr/bin/bash",["-lc",String(p.command||"")],{cwd:r,timeout:seconds*1000,encoding:"utf8",maxBuffer:65536});out={exitCode:x.status===null?-1:x.status,stdout:String(x.stdout||"").slice(-50000),stderr:String(x.stderr||"").slice(-10000),timedOut:!!x.error&&x.error.code==="ETIMEDOUT"}}
else if(p.operation==="start_background"){const r=target(".",true),command=String(p.command||"");if(!/^npm run dev -- --host 127\.0\.0\.1$|^npm run dev -- --port [0-9]{2,5} --host 127\.0\.0\.1$|^python -m http\.server [0-9]{2,5} --bind 127\.0\.0\.1$/.test(command))throw Error("Разрешены только локальные dev-серверы на 127.0.0.1");const child=cp.spawn("/data/data/com.termux/files/usr/bin/bash",["-lc",command],{cwd:r,detached:true,stdio:"ignore"});child.unref();const rows=readTasks();const task={id:crypto.randomUUID(),pid:child.pid,startTicks:startTicks(child.pid),description:String(p.description||"Локальный сервер").slice(0,120),command:command,root:r,startedAt:Date.now()};rows.push(task);writeTasks(rows);out=task}
else if(p.operation==="list_background"){const rows=readTasks(),alive=[];for(const t of rows){if(startTicks(Number(t.pid))===String(t.startTicks))alive.push(t)}writeTasks(alive);out=alive}
else if(p.operation==="stop_background"){const rows=readTasks(),task=rows.find(t=>t.id===p.id);if(!task)throw Error("Фоновая задача уже завершена.");if(startTicks(Number(task.pid))!==String(task.startTicks)){writeTasks(rows.filter(t=>t.id!==p.id));throw Error("Процесс уже завершён; задача удалена из списка.")}try{process.kill(-Number(task.pid),"SIGTERM")}catch(e){try{process.kill(Number(task.pid),"SIGTERM")}catch(x){throw Error("Не удалось остановить процесс.")}}writeTasks(rows.filter(t=>t.id!==p.id));out={stopped:true,id:p.id}}
else if(p.operation==="load_chat_history"){const f="/data/data/com.termux/files/home/.kvas/chats.json";try{const raw=fs.readFileSync(f,"utf8");const parsed=JSON.parse(raw);if(!Array.isArray(parsed))throw Error("Invalid chat archive");out=parsed}catch(e){if(e.code==="ENOENT")out=[];else throw e}}
else if(p.operation==="store_chat_history"){const f="/data/data/com.termux/files/home/.kvas/chats.json",content=String(p.content||"");if(Buffer.byteLength(content,"utf8")>1500000)throw Error("Chat archive exceeds 1.5 MB sync limit");const parsed=JSON.parse(content);if(!Array.isArray(parsed))throw Error("Invalid chat archive");fs.mkdirSync(path.dirname(f),{recursive:true});fs.writeFileSync(f,content,"utf8");out={saved:true,count:parsed.length}}
else throw Error("Unsupported workspace operation");
console.log(JSON.stringify({ok:true,result:out}))
}catch(e){console.error(JSON.stringify({ok:false,error:String(e.message||e)}));process.exit(2)}
""".trimIndent()

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(RUN_COMMAND_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
