const R=(s=>()=>{s|=0;s=s+0x6D2B79F5|0;let t=Math.imul(s^s>>>15,1|s);t=t+Math.imul(t^t>>>7,61|t)^t;return((t^t>>>14)>>>0)/4294967296})(77);
const ri=(a:number,b:number)=>a+Math.floor(R()*(b-a+1));
const pick=<T,>(a:T[]):T=>a[ri(0,a.length-1)];
const sh=<T,>(a:T[]):T[]=>{a=[...a];for(let i=a.length-1;i>0;i--){const j=ri(0,i);[a[i],a[j]]=[a[j],a[i]]}return a};
type Q={t:string,c:string,o:string[],k:number,e:string};
type P={r0:number,r1:number,cs:string,c1:number,st:number,cond:string|null};
const OUT="What is the output? · ما ناتج الكود؟",CNT="How many stars are printed? · كم عدد النجوم المطبوعة؟",WHICH="Which condition prints this pattern? · أي شرط يطبع هذا الشكل؟";
const HOUT="Very hard · صعب جدًا — "+OUT,HCNT="Very hard · صعب جدًا — "+CNT;
const mk=(t:string,c:string,ans:string,wr:string[],e:string):Q=>{const o=sh([ans,...[...new Set(wr)].filter(x=>x!==ans).slice(0,3)]);return{t,c,o,k:o.indexOf(ans),e}};

const fns=new Map<string,(i:number,j:number)=>boolean>();
const fn=(c:string)=>{let f=fns.get(c);if(!f){f=new Function("i","j","return "+c.replace(/abs\(/g,"Math.abs(")) as (i:number,j:number)=>boolean;fns.set(c,f)}return f};
const sim=(p:P)=>{const f=p.cond?fn(p.cond):null,L:string[]=[];for(let i=p.r0;i<=p.r1;i++){let s="";for(let j=p.cs=="i"?i:+p.cs;j<=p.c1;j+=p.st)s+=!f||f(i,j)?"*":" ";L.push(s.trimEnd())}return L.join("\n")};
const code=(p:P)=>`for (int i = ${p.r0}; i <= ${p.r1}; i++) {\n    for (int j = ${p.cs}; j <= ${p.c1}; j${p.st>1?" += "+p.st:"++"}) {\n`+(p.cond?`        if (${p.cond})\n            cout << "*";\n        else\n            cout << " ";\n`:`        cout << "*";\n`)+`    }\n    cout << endl;\n}`;
const mc=(c:string):string=>{const o:string[]=[c.replace(/[ij]/g,x=>x=="i"?"j":"i")];
const ns=[...c.matchAll(/\d+/g)];if(ns.length){const m=pick(ns),x=m.index!;o.push(c.slice(0,x)+(+m[0]+pick([-1,1]))+c.slice(x+m[0].length))}
if(c.includes("=="))o.push(c.replace("==","!="));if(c.includes("||"))o.push(c.replace("||","&&"));if(c.includes("&&"))o.push(c.replace("&&","||"));
if(c.includes("<="))o.push(c.replace("<=",">="));if(c.includes(">="))o.push(c.replace(">=","<="));
const k=c.search(/[ij]/);if(k>=0)o.push(c.slice(0,k)+(c[k]=="i"?"j":"i")+c.slice(k+1));return pick(o)};
const mut=(p:P):P=>{const q={...p},m=ri(0,5);if(p.cond&&m<3)q.cond=mc(p.cond);else if(m==3)q.r1+=pick([-1,1]);else if(m==4)q.c1+=pick([-1,1]);else q.cs=p.cs=="i"?String(p.r0):"i";return q};
const cn=(s:string)=>s.split("*").length-1;
const why=(p:P)=>`Rows i = ${p.r0}..${p.r1}; columns j = ${p.cs}..${p.c1}${p.st>1?" (step "+p.st+")":""}. `+(p.cond?`A star is printed where (${p.cond}) is true, otherwise a space.`:`Every position prints a star.`);
const qOut=(p:P,t=OUT):Q=>{const a=sim(p),w:string[]=[];for(let k=0;k<80&&w.length<3;k++){const x=sim(mut(p));if(x!=a&&!w.includes(x))w.push(x)}return mk(t,code(p),a,w,why(p))};
const qCnt=(p:P,t=CNT):Q=>{const s=sim(p),a=cn(s),w=new Set<number>();for(let k=0;k<60&&w.size<3;k++){const x=cn(sim(mut(p)));if(x!=a)w.add(x)}for(const d of [1,-1,2,-2,3])if(w.size<3&&a+d>0)w.add(a+d);
return mk(t,code(p),String(a),[...w].map(String),`Stars per row: ${s.split("\n").map(l=>cn(l)).join(" + ")} = ${a}.`)};
const qWhich=(p:P):Q=>{const a=sim(p),c=p.cond!,w:string[]=[];for(let k=0;k<120&&w.length<3;k++){const c2=mc(c);if(c2!=c&&!w.includes(c2)&&sim({...p,cond:c2})!=a)w.push(c2)}
return mk(WHICH,code({...p,cond:"..."})+"\n\n/* output:\n"+a+"\n*/","if ("+c+")",w.map(x=>"if ("+x+")"),`Test each condition on the rows of the pattern: only this one reproduces every row. `+why(p))};
const hp=():Q=>{const n=ri(4,7),v=ri(0,2),S=[(i:number)=>2*i-1,(i:number)=>2*i,(i:number)=>i],nm=["2 * i - 1","2 * i","i"];
const dr=(s:number,off:number)=>Array.from({length:n},(_,x)=>{const i=x+1;return(" ".repeat(Math.max(0,n-i+off))+"*".repeat(S[s](i)))}).join("\n");
return mk(HOUT,`for (int i = 1; i <= ${n}; i++) {\n    for (int s = 1; s <= ${n} - i; s++)\n        cout << " ";\n    for (int j = 1; j <= ${nm[v]}; j++)\n        cout << "*";\n    cout << endl;\n}`,dr(v,0),[dr(v,1),dr(v,-1),dr((v+1)%3,0),dr((v+2)%3,0)],`Row i prints ${n} - i spaces first, then ${nm[v]} stars.`)};
const hardProg=():P=>{const n=pick([5,6,7]),k=ri(2,3),m=Math.ceil(n/2),T=[`(i + j) % 2 == 0`,`j <= i`,`i + j > ${n}`,`i % 2 == 1 && j % 2 == 1`,`abs(i - j) <= 1`,`abs(i - ${m}) + abs(j - ${m}) <= ${m-1}`,`i == 1 || i == ${n} || j == 1 || j == ${n} || i == j`,`i * j % ${k+1} == 0`,`i == j || j == ${n+1} - i || i == ${m}`,`(i == 1 || i == ${n}) && j > 1 && j < ${n} || (j == 1 || j == ${n}) && i > 1 && i < ${n}`,`i <= j && i + j <= ${n+1}`,`i >= j && i + j >= ${n+1}`,`(i + j) % ${k} == 0 || i == j`,`i % ${k} == 0 || j % ${k} == 0`];
return{r0:1,r1:n,cs:"1",c1:n,st:pick([1,1,2]),cond:pick(T)}};

const B=(cond:string|null,r:[number,number]=[1,5],c:[string,number]=["1",5]):P=>({r0:r[0],r1:r[1],cs:c[0],c1:c[1],st:1,cond});
const progs:P[]=[B(null,[0,10],["0",10]),B(null,[0,10],["i",10]),B("i == 0 || i == 10 || j == 0 || j == 10",[0,10],["0",10]),
B("i == 1 || i == 3 || i == 5 || j == 1"),B("i == 1 || j == 3"),B("i == 5 || j == 1"),B("i == 1 || i == 3 || j == 1"),B("j == 1 || i == 3 || j == 5"),B("i == 1 || i == 5 || j == 3"),
B("j == 1 || i == j || j == 5"),B("i == j || j + i == 6"),B("i == 1 || i == 5 || i + j == 6"),B("j == 1 || j == 5 || i == j && i <= 3 || i + j == 6 && i <= 3"),
B("j == 1 || j + i == 6 && i <= 3 || i == j && i >= 3 || i == 3 && j <= 3"),B("i + j == 4 && i <= 3 || j == 3 || i == 5"),B("i == 3 || j == 5 || j == 1 && i <= 3"),
B("i == 1 || i == 3 || i == 5 || j == 1 || j == 5 && i >= 3"),B("i == 1 || i == 3 || i == 5 || j == 1 && i >= 3 || j == 5 && i <= 3"),B("i == 1 || i == 3 || i == 5 || j == 5"),
B("i == 1 || i == 3 || i == 5 || j == 1 && i <= 3 || j == 5 && i >= 3"),B("i == 1 || i + j == 6"),B("i == 1 || i == 3 || i == 5 || j == 1 || j == 5"),
B("i == 1 || i == 3 || i == 5 || j == 5 || j == 1 && i <= 3"),B("i + j == 4 && i <= 3 || j == 3 || i == 5 && j <= 5 || j == 8 || j == 12 || i == 1 && j >= 8 || i == 5 && j >= 8",[1,5],["1",12])];

const seen=new Set<string>();
const add=(q:Q,arr:Q[])=>{const k=q.t+q.c+q.o.join("|");if(seen.has(k))return false;seen.add(k);arr.push(q);return true};
const base:Q[]=[],hard:Q[]=[];
for(const p of progs){add(qOut(p),base);add(qCnt(p),base);if(p.cond)add(qWhich(p),base)}
let n=0,tries=0;while(n<24&&tries++<500){const p=hardProg();if(sim(p).indexOf("*")<0)continue;if(add(n<14?qOut(p,HOUT):qCnt(p,HCNT),hard))n++}
n=0;tries=0;while(n<6&&tries++<100)if(add(hp(),hard))n++;
const QS=[...sh(base),...sh(hard)];

const H=String.raw`<!doctype html><html lang=ar dir=rtl><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name=color-scheme content="light dark"><title>اختبار درس طباعة الأشكال للدكتورة سمية C++</title>
<link rel=preconnect href="https://fonts.googleapis.com"><link rel=preconnect href="https://fonts.gstatic.com" crossorigin>
<link rel=stylesheet href="https://fonts.googleapis.com/css2?family=Caveat:wght@600&family=Fira+Code:wght@400;500&family=Vazirmatn:wght@400;500;700&display=swap">
<style>
:root{--paper:#e9eae8;--sheet:#f7f7f5;--ink:#1a1c1f;--rule:#3b3f45;--faint:#c8cacc;--pencil:#565c64;--red:#c2231f;--blue:#1d4e89;--hl:#e8eaed;color-scheme:light}
@media(prefers-color-scheme:dark){:root{--paper:#101214;--sheet:#1a1d20;--ink:#e9eae8;--rule:#8a9098;--faint:#33383d;--pencil:#a6adb6;--red:#ff7b73;--blue:#8fb8ee;--hl:#24282c;color-scheme:dark}}
*{box-sizing:border-box}html{-webkit-text-size-adjust:100%}
body{margin:0;background:var(--paper);color:var(--ink);font:400 16px/1.6 Vazirmatn,"Segoe UI",Tahoma,system-ui,sans-serif;padding-bottom:max(40px,env(safe-area-inset-bottom))}
.ttl{margin:0;text-wrap:balance;background:var(--paper);color:var(--ink);text-align:center;font:700 clamp(15px,4.4vw,20px)/1.45 Vazirmatn,"Segoe UI",system-ui,sans-serif;padding:max(11px,env(safe-area-inset-top)) 16px 11px}
.hd{position:sticky;top:0;z-index:5;background:var(--paper)}
.strip{background:var(--ink);color:var(--paper);text-align:center;font:500 12.5px/1 Vazirmatn,system-ui,sans-serif;padding:max(9px,env(safe-area-inset-top)) 12px 9px;direction:ltr;letter-spacing:.02em}
.tally{display:flex;align-items:center;max-width:820px;margin:0 auto;padding:9px max(16px,env(safe-area-inset-right)) 0 max(16px,env(safe-area-inset-left))}
#n{font:500 14px/1 "Fira Code",ui-monospace,monospace;direction:ltr;unicode-bidi:isolate}
#sc{margin-inline-start:auto;color:var(--red);font:600 24px/1 Caveat,cursive}
.rail{height:3px;background:var(--faint);margin-top:9px}#f{height:100%;width:0;background:var(--ink);transition:width .25s}
main{max-width:820px;margin:0 auto;padding:14px max(16px,env(safe-area-inset-right)) 0 max(16px,env(safe-area-inset-left))}
.sheet{background:var(--sheet);border:1.5px solid var(--rule);display:grid;grid-template-columns:46px minmax(0,1fr)}
.no{border-inline-end:1.5px solid var(--rule);padding-top:16px;text-align:center;font:500 15px/1 "Fira Code",ui-monospace,monospace;color:var(--pencil)}
.q{padding:14px 16px}
.stamp{display:inline-block;border:1.5px solid var(--red);color:var(--red);font:700 12px/1 Vazirmatn,system-ui,sans-serif;padding:4px 9px;border-radius:3px;transform:rotate(-2deg);margin-bottom:10px}
#t span{display:block;font-weight:700;font-size:17px}#t small{display:block;color:var(--pencil);font-size:13px;direction:ltr;text-align:left}
#t:focus{outline:0}
pre{margin:0;font:400 14.5px/1.65 "Fira Code",ui-monospace,Menlo,Consolas,monospace;font-variant-ligatures:none;font-feature-settings:"liga" 0,"calt" 0;white-space:pre-wrap;overflow-wrap:anywhere;direction:ltr;text-align:left}
#c{margin-top:12px;padding:4px 0 4px 14px;border-left:3px solid var(--faint);overflow-x:auto}
.k{font-weight:500}.s{color:var(--blue)}
#o{grid-column:1/-1;direction:ltr;display:grid;grid-template-columns:1fr;gap:1.5px;background:var(--rule);border-top:1.5px solid var(--rule)}
@media(min-width:640px){#o{grid-template-columns:1fr 1fr}}
#o button{position:relative;overflow:hidden;display:grid;grid-template-columns:30px minmax(0,1fr);gap:12px;align-items:start;min-height:54px;padding:12px 16px;border:0;background:var(--sheet);color:inherit;text-align:left;direction:ltr;font:inherit;cursor:pointer}
#o:not(.lk) button:hover{background:var(--hl)}
#o.lk button{cursor:default}
.lt{position:relative;display:grid;place-items:center;width:30px;height:30px;font:700 17px/1 Vazirmatn,system-ui,sans-serif}
.ring{position:absolute;left:-10px;top:-6px;width:50px;height:42px;fill:none;stroke:var(--red);stroke-width:2.2;stroke-linecap:round;stroke-dasharray:1;animation:d .45s ease-out}
.w::after{content:"";position:absolute;inset:0;background:linear-gradient(to top right,transparent calc(50% - 1.3px),var(--red) calc(50% - 1.3px),var(--red) calc(50% + 1.3px),transparent calc(50% + 1.3px));animation:s .3s ease-out}
@keyframes d{from{stroke-dashoffset:1}}@keyframes s{from{clip-path:inset(0 100% 0 0)}}
#e{grid-column:1/-1;border-top:1.5px solid var(--rule);padding:12px 16px;display:grid;gap:4px}
#e b{font-weight:700}#e b i{font-style:normal;color:var(--red);font-size:1.15em}
#e p{margin:0;color:var(--pencil);font:600 20px/1.35 Caveat,cursive;direction:ltr;text-align:left}
.foot{grid-column:1/-1;border-top:1.5px solid var(--rule);padding:12px 16px}
.btn{min-height:44px;padding:0 24px;border:0;border-radius:2px;background:var(--ink);color:var(--paper);font:700 16px Vazirmatn,system-ui,sans-serif;cursor:pointer}
.btn:hover{opacity:.88}
.end{display:block;text-align:center;padding:34px 16px 28px}
.mark{position:relative;display:inline-block;padding:30px 46px;color:var(--red);font:600 clamp(48px,14vw,76px)/1 Caveat,cursive;direction:ltr}
.mark .ring{left:0;top:0;width:100%;height:100%;stroke-width:.8}
.msg{margin:14px 0 20px;font-size:18px;font-weight:500}
:focus-visible{outline:2.5px solid var(--blue);outline-offset:2px}[hidden]{display:none!important}
@media(prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}
</style></head><body>
<h1 class=ttl>اختبار درس طباعة الأشكال للدكتورة سمية <span dir=ltr>C++</span></h1>
<div class=hd><div class=strip>Developed by Maggd Alosimi</div><div class=tally><span id=n></span><span id=sc></span></div><div class=rail><div id=f></div></div></div>
<main><div class=sheet id=q><div class=no id=no></div><div class=q><div id=lv></div><div id=t tabindex=-1></div><pre id=c></pre></div><div id=o></div><div id=e hidden></div><div class=foot id=ft hidden><button class=btn id=nx></button></div></div>
<div class="sheet end" id=end hidden></div></main>
<script>
const Q=__Q__;const $=s=>document.getElementById(s);
const KEY="stars:"+Q.length+":"+Q.slice(0,6).map(q=>q.c.length+""+q.k).join("");
let a=[],i=0,lock=false;
try{const z=JSON.parse(localStorage.getItem(KEY)||"null");if(z&&Array.isArray(z.a)){a=z.a.slice(0,Q.length);let n=0;while(a[n]!=null)n++;i=Math.min(+z.i||0,n)}}catch(e){}
const save=()=>{try{localStorage.setItem(KEY,JSON.stringify({i:i,a:a}))}catch(e){}};
const score=()=>a.reduce((s,v,j)=>s+(v===Q[j].k?1:0),0);
function restart(){try{localStorage.removeItem(KEY)}catch(e){}location.reload()}
const esc=s=>s.replace(/&/g,"&amp;").replace(/</g,"&lt;");
const hl=s=>esc(s).replace(/("(?:\\.|[^"\\])*")|\b(int|float|double|char|bool|short|long|for|while|do|if|else|switch|case|default|break|continue|cout|cin|endl|return|true|false)\b/g,(m,a)=>a?'<span class=s>'+m+'</span>':'<span class=k>'+m+'</span>');
const RING='<svg class=ring viewBox="0 0 44 34" preserveAspectRatio=none aria-hidden=true><path pathLength=1 d="M8 10C15 2 36 3 40 13S33 32 20 31 2 24 6 14"/></svg>';
function head(){$("n").textContent=(i+1)+" / "+Q.length;$("sc").textContent="✓ "+score();$("f").style.width=(100*i/Q.length)+"%"}
function show(){const q=Q[i],h=q.t.indexOf("Very hard")==0,p=(h?q.t.split(" — ")[1]:q.t).split(" · ");lock=false;head();
$("no").textContent=i+1;$("lv").innerHTML=h?'<span class=stamp>صعب جدًا</span>':"";
$("t").innerHTML="<span>"+esc(p[1]||"")+"</span><small>"+esc(p[0])+"</small>";$("c").innerHTML=hl(q.c);$("c").hidden=!q.c;
$("o").className="";$("o").innerHTML=q.o.map((x,k)=>'<button data-k="'+k+'"><span class=lt>'+"ABCD"[k]+":</span><pre>"+esc(x)+"</pre></button>").join("");
$("e").hidden=true;$("ft").hidden=true;if(a[i]!=null)mark(a[i])}
function mark(k){lock=true;$("o").className="lk";const q=Q[i],ok=k==q.k,c=$("o").children;c[q.k].querySelector(".lt").insertAdjacentHTML("beforeend",RING);if(!ok)c[k].className="w";
$("e").innerHTML=(ok?"<b>صحيح <i>✓</i></b>":"<b>خطأ <i>✗</i> — الإجابة الصحيحة "+"ABCD"[q.k]+"</b>")+"<p>"+esc(q.e)+"</p>";$("e").hidden=false;
$("nx").textContent=i==Q.length-1?"عرض النتيجة":"السؤال التالي";$("ft").hidden=false;head()}
$("o").onclick=e=>{const b=e.target.closest("button");if(!b||lock)return;a[i]=+b.dataset.k;save();mark(a[i])};
$("nx").onclick=()=>{i++;save();if(i<Q.length){show();window.scrollTo(0,0);$("t").focus({preventScroll:true})}else fin()};
function fin(){$("q").hidden=true;$("f").style.width="100%";const sc=score(),p=Math.round(100*sc/Q.length),m=p>=85?"ممتاز":p>=60?"جيد":"راجع المادة ثم أعد المحاولة";
$("end").hidden=false;$("end").innerHTML="<div><div class=mark><span>"+sc+"/"+Q.length+"</span>"+RING+"</div><p class=msg>"+m+" — "+p+"%</p><button class=btn onclick=restart()>أعد الاختبار</button></div>";window.scrollTo(0,0)}
if(i>=Q.length)fin();else show();
</script></body></html>`;
const page=H.replace("__Q__",()=>JSON.stringify(QS).replace(/</g,"\\u003c"));
Bun.serve({port:Number(Bun.env.PORT)||3000,fetch(r){const p=new URL(r.url).pathname;if(p=="/api")return Response.json(QS);return new Response(page,{headers:{"content-type":"text/html; charset=utf-8"}})}});
