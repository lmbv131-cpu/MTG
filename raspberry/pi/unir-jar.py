"""mtglocal.jar from the two halves the public repo keeps (like Unir in instalar.ps1):
motor.jar (engine and libraries, with the manifest) and app.jar (our code). If a name is in both, app.jar's wins.
Usage: python3 unir-jar.py app.jar motor.jar out.jar"""
import sys
import zipfile

app, motor, out = sys.argv[1:4]
with zipfile.ZipFile(app) as za, zipfile.ZipFile(motor) as zm, \
        zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zo:
    mine = set(za.namelist())
    for src, skip in ((zm, mine), (za, set())):
        for info in src.infolist():
            if info.filename in skip:
                continue
            zo.writestr(info, b"" if info.is_dir() else src.read(info), zipfile.ZIP_DEFLATED)
