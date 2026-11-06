import os,pathlib,subprocess,zipfile,shutil,struct
P=pathlib.Path(__file__).resolve().parents[1];T=pathlib.Path(os.environ.get('SPTCC_TOOLS',str(P/'.tools')))
ANDROID=pathlib.Path(os.environ['ANDROID_JAR'])
APKTOOL=pathlib.Path(os.environ['APKTOOL_JAR'])
SIGNER=pathlib.Path(os.environ['APKSIGNER_JAR'])
KEYTOOL=os.environ.get('KEYTOOL','keytool')
KEYSTORE=pathlib.Path(os.environ.get('SPTCC_KEYSTORE',str(P/'.local-signing/module.p12')))
PASSWORD=os.environ.get('SPTCC_SIGNING_PASSWORD','local-development-only')
LIBS=pathlib.Path(os.environ['ANDROID_LIBS'])
LINKER=os.environ['RUST_LLD']
def run(*args):subprocess.run([str(a) for a in args],check=True)
for d in ['classes','dex','build','framework','native']: (P/d).mkdir(exist_ok=True)
run('rustc',P/'native/diagnostic.rs','--crate-type=cdylib','--target','aarch64-linux-android','--edition=2021','-C','opt-level=2','-C','panic=abort','-C','linker='+LINKER,'-C','linker-flavor=ld.lld','-C','link-arg=-soname=libsptcccompat.so','-L','native='+str(LIBS),'-l','c','-l','log','-o',P/'native/libsptcccompat.so')
run('javac','--release','8','-encoding','UTF-8','-cp',str(ANDROID)+os.pathsep+str(T/'xposed-api-82.jar'),'-d',P/'classes',*sorted((P/'src').rglob('*.java')))
run('java','-cp',T/'r8.jar','com.android.tools.r8.D8','--min-api','26','--lib',ANDROID,'--classpath',T/'xposed-api-82.jar','--output',P/'dex',*sorted((P/'classes').rglob('*.class')))
run('java','-jar',APKTOOL,'b',P/'apk','-p',P/'framework','-o',P/'build/resources.apk')
with zipfile.ZipFile(P/'build/resources.apk') as src,zipfile.ZipFile(P/'build/unsigned.apk','w') as out:
    for i in src.infolist():
        if i.filename!='classes.dex' and not i.filename.startswith('META-INF/'):out.writestr(i,src.read(i.filename))
    out.write(P/'dex/classes.dex','classes.dex')
    native=P/'native/libsptcccompat.so'
    if native.exists():
        info=zipfile.ZipInfo('lib/arm64-v8a/libsptcccompat.so')
        info.compress_type=zipfile.ZIP_STORED
        offset=out.fp.tell()+30+len(info.filename.encode('utf-8'))
        padding=(-offset)%16384
        if 0<padding<4:padding+=16384
        if padding:info.extra=struct.pack('<HH',0xd935,padding-4)+bytes(padding-4)
        out.writestr(info,native.read_bytes())
key=KEYSTORE
key.parent.mkdir(parents=True,exist_ok=True)
os.environ['SPTCC_SIGNING_PASSWORD']=PASSWORD
if not key.exists():run(KEYTOOL,'-genkeypair','-keystore',key,'-storepass',PASSWORD,'-keypass',PASSWORD,'-alias','sptcc-local','-dname','CN=Local SPTCC Compatibility','-keyalg','RSA','-keysize','2048','-validity','3650','-storetype','PKCS12')
run('java','-jar',SIGNER,'sign','--ks',key,'--ks-pass','env:SPTCC_SIGNING_PASSWORD','--ks-key-alias','sptcc-local','--out',P/'build/sptcc-compat.apk',P/'build/unsigned.apk')
run('java','-jar',SIGNER,'verify','--verbose',P/'build/sptcc-compat.apk')
print('BUILT',P/'build/sptcc-compat.apk')
