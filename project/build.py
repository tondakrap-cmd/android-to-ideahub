"""Build a test APK using an existing JDK 17 and Android SDK (no Gradle required)."""
import argparse, hashlib, json, os, pathlib, secrets, subprocess, zipfile

root=pathlib.Path(__file__).resolve().parent
p=argparse.ArgumentParser()
p.add_argument('--java-home',required=True,type=pathlib.Path)
p.add_argument('--build-tools',required=True,type=pathlib.Path)
p.add_argument('--android-jar',required=True,type=pathlib.Path)
p.add_argument('--output',type=pathlib.Path,default=root/'build'/'Tabule-Link-0.7.2.apk')
p.add_argument('--release',action='store_true',help='vyžadovat finální klíč (selže, pokud chybí)')
p.add_argument('--test-key',action='store_true',help='podepsat testovacím klíčem i když finální existuje')
a=p.parse_args()
build=root/'build'; build.mkdir(exist_ok=True)
classes=build/'classes'; classes.mkdir(exist_ok=True)
dex=build/'dex'; dex.mkdir(exist_ok=True)
env=dict(os.environ);env['JAVA_HOME']=str(a.java_home.resolve());env['PATH']=str(a.java_home.resolve()/'bin')+os.pathsep+env['PATH']
def run(*args): subprocess.run([str(x) for x in args],check=True,env=env,cwd=root)
exe='.exe' if os.name=='nt' else ''
bt=a.build_tools.resolve();java=a.java_home.resolve()/'bin'/('java'+exe);javac=a.java_home.resolve()/'bin'/('javac'+exe)
android=a.android_jar.resolve();zxing=root/'libs'/'zxing-core-3.5.3.jar'
run(bt/('aapt2'+exe),'compile','--dir',root/'res','-o',build/'resources.zip')
run(bt/('aapt2'+exe),'link','-o',build/'base.apk','--manifest',root/'AndroidManifest.xml','-I',android,'--java',build/'generated','-A',root/'assets',build/'resources.zip')
sources=list((root/'src').rglob('*.java'))+list((build/'generated').rglob('*.java'))
args=build/'javac.args'
with args.open('w',encoding='utf-8') as f:
 for item in ['-encoding','UTF-8','--release','8','-Xlint:unchecked','-classpath',str(android)+os.pathsep+str(zxing),'-d',classes]+sources:
  f.write('"'+str(item).replace('\\','/')+'"\n')
run(javac,'@'+str(args))
jar=build/'classes.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
 for c in classes.rglob('*.class'):z.write(c,c.relative_to(classes).as_posix())
run(java,'-cp',bt/'lib'/'d8.jar','com.android.tools.r8.D8','--release','--min-api','26','--lib',android,'--output',dex,jar,zxing)
unsigned=build/'unsigned.apk'
with zipfile.ZipFile(build/'base.apk') as src,zipfile.ZipFile(unsigned,'w',zipfile.ZIP_DEFLATED) as z:
 for item in src.infolist():z.writestr(item,src.read(item.filename))
 for c in dex.glob('*.dex'):z.write(c,c.name)
run(bt/('zipalign'+exe),'-f','-p','4',unsigned,build/'aligned.apk')
signing=root/'.signing';signing.mkdir(exist_ok=True)
# Finální (release) klíč: všechny vydané verze MUSÍ být podepsány stejným klíčem, jinak Android
# odmítne aktualizaci. Cestu lze přepsat proměnnými TABULE_KEYSTORE / TABULE_KEYSTORE_PASS_FILE.
release_key=pathlib.Path(os.environ.get('TABULE_KEYSTORE',signing/'release'/'tabule-link-release.p12'))
release_secret=pathlib.Path(os.environ.get('TABULE_KEYSTORE_PASS_FILE',release_key.parent/'password.txt'))
if a.release and not (release_key.exists() and release_secret.exists()):
 raise SystemExit(f'Chybí finální klíč {release_key} nebo heslo {release_secret}.')
if release_key.exists() and release_secret.exists() and not a.test_key:
 key,secret=release_key,release_secret
 print('Podepisuji FINÁLNÍM klíčem:',key)
else:
 secret=signing/'password.txt'
 if not secret.exists():secret.write_text(secrets.token_urlsafe(32),encoding='ascii')
 key=signing/'test-signing.p12'
 if not key.exists():
  run(a.java_home.resolve()/'bin'/('keytool'+exe),'-genkeypair','-keystore',key,'-storetype','PKCS12','-storepass:file',secret,'-keypass:file',secret,
      '-alias','tabule-link','-keyalg','RSA','-keysize','3072','-validity','3650','-dname','CN=Tabule Link Test Build, O=Local Development, C=CZ')
 print('POZOR: podepisuji TESTOVACÍM klíčem (takové APK nejde instalovat jako aktualizaci vydané verze).')
a.output=a.output.resolve();a.output.parent.mkdir(parents=True,exist_ok=True)
run(java,'-jar',bt/'lib'/'apksigner.jar','sign','--ks',key,'--ks-pass','file:'+str(secret),'--out',a.output,build/'aligned.apk')
run(java,'-jar',bt/'lib'/'apksigner.jar','verify','--verbose','--print-certs',a.output)
run(bt/('zipalign'+exe),'-c','-p','4',a.output)
print('APK:',a.output)
print('SHA256:',hashlib.sha256(a.output.read_bytes()).hexdigest())
