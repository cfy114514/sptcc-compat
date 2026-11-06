#![no_std]
use core::{ffi::{c_char,c_int,c_void},panic::PanicInfo,ptr};
#[repr(C)]
#[derive(Clone,Copy)]
struct SigAction { flags:c_int,handler:usize,mask:u64,restorer:usize }
#[repr(C)] struct Stack { sp:*mut c_void,flags:c_int,size:usize }
#[repr(C,align(16))] struct MContext { fault:u64,regs:[u64;31],sp:u64,pc:u64,pstate:u64 }
#[repr(C)] struct UContext { flags:u64,link:*mut c_void,stack:Stack,mask:u64,padding:[u8;120],machine:MContext }
extern "C" {
 fn open(p:*const c_char,flags:c_int,...)->c_int;
 fn read(fd:c_int,p:*mut c_void,n:usize)->isize;
 fn write(fd:c_int,p:*const c_void,n:usize)->isize;
 fn close(fd:c_int)->c_int;
 fn getpid()->c_int;
 fn fork()->c_int;
 fn __errno()->*mut c_int;
 fn sigaction(sig:c_int,new:*const SigAction,old:*mut SigAction)->c_int;
 fn pthread_atfork(a:Option<unsafe extern "C" fn()>,b:Option<unsafe extern "C" fn()>,c:Option<unsafe extern "C" fn()>)->c_int;
 fn __android_log_write(level:c_int,tag:*const c_char,text:*const c_char)->c_int;
 fn _exit(status:c_int)->!;
}
type HookFn=unsafe extern "C" fn(*mut c_void,*mut c_void,*mut *mut c_void)->c_int;
#[repr(C)] pub struct NativeApi { version:u32,hook:HookFn,unhook:*mut c_void }
type SigActionFn=unsafe extern "C" fn(c_int,*const SigAction,*mut SigAction)->c_int;
static mut SIGACTION_BACKUP:*mut c_void=ptr::null_mut();
static mut PARENT:c_int=0;
static mut FORK_BACKUP:*mut c_void=ptr::null_mut();
static mut EARLY:bool=true;
unsafe extern "C" fn hooked_fork()->c_int {
 let caller:usize;core::arch::asm!("mov {}, x30",out(reg)caller);
 let mut b=Buffer{data:[0;2048],used:0};b.text(b"FORK_CALL caller=");b.hex(caller as u64);
 if EARLY && getpid()==PARENT {
  b.text(b" startup compatibility EAGAIN\n");b.flush();*__errno()=11;return -1;
 }
 b.text(b" passthrough\n");b.flush();
 let original:unsafe extern "C" fn()->c_int=core::mem::transmute(FORK_BACKUP);original()
}
#[no_mangle]
pub unsafe extern "C" fn Java_io_github_cfy_sptcccompat_MainHook_nativeApplicationLoaded(_env:*mut c_void,_class:*mut c_void){EARLY=false;}
static mut FD:c_int=-1;
static mut OLD:SigAction=SigAction{flags:0,handler:0,mask:0,restorer:0};
unsafe fn real_sigaction(sig:c_int,action:*const SigAction,old:*mut SigAction)->c_int {
 if SIGACTION_BACKUP.is_null(){sigaction(sig,action,old)}else{let f:SigActionFn=core::mem::transmute(SIGACTION_BACKUP);f(sig,action,old)}
}
unsafe extern "C" fn hooked_sigaction(sig:c_int,action:*const SigAction,old:*mut SigAction)->c_int {
 if sig!=11||getpid()==PARENT{return real_sigaction(sig,action,old);}
 if action.is_null(){if !old.is_null(){*old=OLD;}return 0;}
 if !old.is_null(){*old=OLD;}
 OLD=*action;
 let replacement=SigAction{flags:((*action).flags&0x08000000)|4,handler:signal_record as *const () as usize,mask:(*action).mask,restorer:0};
 let mut b=Buffer{data:[0;2048],used:0};b.text(b"CHILD_SIGACTION handler=");b.hex((*action).handler as u64);b.text(b"\n");b.flush();
 real_sigaction(sig,&replacement,ptr::null_mut())
}
struct Buffer { data:[u8;2048],used:usize }
impl Buffer {
 fn text(&mut self,s:&[u8]){for &c in s {if self.used<self.data.len(){self.data[self.used]=c;self.used+=1;}}}
 fn hex(&mut self,x:u64){self.text(b"0x");for shift in (0..16).rev(){let n=((x>>(shift*4))&15) as u8;self.text(&[if n<10 {b'0'+n}else{b'a'+n-10}]);}}
 unsafe fn flush(&self){if FD>=0{write(FD,self.data.as_ptr() as _,self.used);}}
}
unsafe extern "C" fn signal_record(sig:c_int,_info:*mut c_void,context:*mut c_void){
 let c=&*(context as *const UContext);
 let mut b=Buffer{data:[0;2048],used:0};
 b.text(b"\nSPTCC_CHILD_SIGNAL pid=");b.hex(getpid() as u64);b.text(b" signal=");b.hex(sig as u64);
 b.text(b" pc=");b.hex(c.machine.pc);b.text(b" lr=");b.hex(c.machine.regs[30]);b.text(b" sp=");b.hex(c.machine.sp);
 b.text(b" fault=");b.hex(c.machine.fault);b.text(b"\nREGS\n");
 for i in 0..31{b.hex(i as u64);b.text(b"=");b.hex(c.machine.regs[i]);b.text(b" ");}
 b.text(b"\nMAPS\n");b.flush();
 let maps=open(b"/proc/self/maps\0".as_ptr() as _,0);
 if maps>=0 {let mut chunk=[0u8;4096];for _ in 0..64 {let n=read(maps,chunk.as_mut_ptr() as _,4096);if n<=0 {break;}if FD>=0 {write(FD,chunk.as_ptr() as _,n as usize);}}close(maps);}
 let old=OLD;
 real_sigaction(sig,&old,ptr::null_mut());
 if old.handler>1 {if old.flags&4!=0 {let f:unsafe extern "C" fn(c_int,*mut c_void,*mut c_void)=core::mem::transmute(old.handler);f(sig,_info,context);}else{let f:unsafe extern "C" fn(c_int)=core::mem::transmute(old.handler);f(sig);}}
}
unsafe extern "C" fn child(){
 let mut b=Buffer{data:[0;2048],used:0};b.text(b"\nFORK_CHILD pid=");b.hex(getpid() as u64);b.text(b"\n");b.flush();
 real_sigaction(11,ptr::null(),ptr::addr_of_mut!(OLD));
 let old=OLD;
 let replacement=SigAction{flags:(old.flags&0x08000000)|4,handler:signal_record as *const () as usize,mask:0,restorer:0};
 real_sigaction(11,&replacement,ptr::null_mut());
}
#[no_mangle]
pub unsafe extern "C" fn native_init(api:*const NativeApi)->*mut c_void {
 if !api.is_null(){let result=((*api).hook)(sigaction as *const () as _,hooked_sigaction as *const () as _,ptr::addr_of_mut!(SIGACTION_BACKUP));
 __android_log_write(4,b"SptccCompat\0".as_ptr() as _,if result==0{b"native signal observer installed\0".as_ptr()}else{b"native signal observer failed\0".as_ptr()} as _);}
 if !api.is_null(){let result=((*api).hook)(fork as *const () as _,hooked_fork as *const () as _,ptr::addr_of_mut!(FORK_BACKUP));
 __android_log_write(4,b"SptccCompat\0".as_ptr() as _,if result==0{b"startup fork compatibility installed\0".as_ptr()}else{b"startup fork compatibility failed\0".as_ptr()} as _);}
 ptr::null_mut()
}
#[no_mangle]
pub unsafe extern "C" fn JNI_OnLoad(_vm:*mut c_void,_reserved:*mut c_void)->c_int {
 FD=open(b"/data/user/0/com.jiuyu.sptcc.cordova/cache/sptcc-native-crash.txt\0".as_ptr() as _,1|64|1024|524288,384u32);
 PARENT=getpid();
 pthread_atfork(None,None,Some(child));
 __android_log_write(4,b"SptccCompat\0".as_ptr() as _,b"native child diagnostic armed\0".as_ptr() as _);
 0x00010006
}
#[panic_handler] fn panic(_p:&PanicInfo)->!{unsafe{_exit(127)}}
