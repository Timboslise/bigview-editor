# BigView

A fast, read-only viewer for very large text files (GBs). It never loads the file into memory.
A background thread builds a sparse index of line offsets (every 64th line), and only the lines
on screen are read from disk.

## pom file

We did not include a pom file. All the necessary environment information will be found in the .idea folder

Shared project settings (usually committed):                                                                                                                                                                                                                                                                 
- misc.xml: which JDK and language level the project uses.                                                                                                                                                                                                                                                   
- modules.xml and editor.iml: the project structure, such as that src is the source folder.                                                                                                                                                                                                                  
- .idea/.gitignore: IntelliJ created this itself, and it already ignores some of its own personal files.

## Build

Requires JDK 21 (no other dependencies).

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1           # jar + dist\BigView\BigView.exe
powershell -ExecutionPolicy Bypass -File .\build.ps1 -JarOnly   # only out\lib\bigview.jar
```

The parts of the command:
- powershell starts a new PowerShell process to run the script.
- -ExecutionPolicy Bypass lets the script run. By default Windows blocks .ps1 files you wrote or downloaded yourself. This switch lifts the block only for this one run and doesn't change any system setting.
- -File .\build.ps1 is the script to run. .\ means "in the current folder", so run it from C:\Users\timver\ideaProjects\editor.
- -JarOnly is an option I added to the script. It skips the slowest step (making the .exe), which is handy if you only want to try a code change quickly.

What build.ps1 does, step by step:
1. Cleans up by deleting the old out and dist folders, so nothing stale is left behind.
2. Compiles with javac, which turns the .java files in src\ into .class files in out\classes.
3. Packs with jar, which bundles those classes into one file, out\lib\bigview.jar. You can run that with java -jar out\lib\bigview.jar <file>, but only on a machine where Java is installed.
4. Packages with jpackage (skipped with -JarOnly). This creates dist\BigView\BigView.exe together with a small, trimmed copy of Java. That's why the folder works on its own, even on a PC without Java. Its options:
    - --add-modules java.desktop,jdk.charsets includes only the parts of Java the app needs (the window toolkit and the extra text encodings). This keeps the folder at about 70 MB instead of about 300 MB.
    - --jlink-options ... strips debug info and docs from that Java copy, which makes it smaller still.
    - --java-options ... sets memory settings: start small (-Xms32m), never use more than 768 MB (-Xmx768m), and hand unused memory back to Windows.  

`dist\BigView` is self-contained (it bundles a trimmed Java runtime). Copy the folder anywhere and use
`BigView.exe` in Explorer's *Open with…*. Without the exe: `java -jar out\lib\bigview.jar <file>`.

## Keys

| Action | Key |
|---|---|
| Open / Reload | Ctrl+O / F5 (or drag a file onto the window) |
| Scroll | Arrows, PgUp/PgDn, mouse wheel (Shift+wheel = horizontal) |
| Top / bottom | Ctrl+Home / Ctrl+End |
| Line start / end | Home / End |
| Go to line | Ctrl+G |
| Find / Find next | Ctrl+F / F3 (Esc cancels a running search) |
| Select / copy | Mouse drag, Shift+click, Ctrl+A / Ctrl+C (copies at most 50 MB) |
| Zoom | Ctrl+wheel, Ctrl+= / Ctrl+- / Ctrl+0 |

## Limitations

- View only: no editing, word wrap or syntax highlighting.
- Lines longer than 10,000 bytes are shown split over several numbered lines.
- Encodings: UTF-8 and Windows-1252 (UTF-16 files are not supported).
- Case-insensitive search folds ASCII letters only.
