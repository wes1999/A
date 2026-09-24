import http from 'node:http';
import { randomBytes, randomUUID, createHash, timingSafeEqual, pbkdf2 as pbkdf2Callback } from 'node:crypto';
import { promisify } from 'node:util';
import pg from 'pg';

const pbkdf2=promisify(pbkdf2Callback);
const pool=new pg.Pool({connectionString:process.env.DATABASE_URL,max:8,connectionTimeoutMillis:9000,idleTimeoutMillis:30000});
const PORT=Number(process.env.PORT||3000);
const ADMIN_CODE=process.env.ADMIN_CODE||'';
if(ADMIN_CODE.length<16||!process.env.DATABASE_URL)throw Error('Configure ADMIN_CODE (16+ caracteres) e DATABASE_URL');
const sqlSchema=[
"CREATE TABLE IF NOT EXISTS accounts(id UUID PRIMARY KEY,name TEXT NOT NULL,account_key TEXT NOT NULL UNIQUE,pin_salt TEXT NOT NULL,pin_hash TEXT NOT NULL,balance_cents BIGINT NOT NULL DEFAULT 0 CHECK(balance_cents>=0),created_at BIGINT NOT NULL,status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','SUSPENDED')))",
"CREATE TABLE IF NOT EXISTS account_keys(key TEXT PRIMARY KEY,account_id UUID NOT NULL REFERENCES accounts(id),created_at BIGINT NOT NULL)",
"CREATE INDEX IF NOT EXISTS account_keys_account_idx ON account_keys(account_id)",
"CREATE TABLE IF NOT EXISTS sessions(token_hash TEXT PRIMARY KEY,account_id UUID NOT NULL REFERENCES accounts(id),expires_at BIGINT NOT NULL)",
"CREATE INDEX IF NOT EXISTS sessions_account_idx ON sessions(account_id)",
"CREATE TABLE IF NOT EXISTS admin_sessions(token_hash TEXT PRIMARY KEY,expires_at BIGINT NOT NULL)",
"CREATE TABLE IF NOT EXISTS auth_limits(bucket TEXT PRIMARY KEY,hits INT NOT NULL,reset_at BIGINT NOT NULL)",
"CREATE TABLE IF NOT EXISTS transfers(id UUID PRIMARY KEY,from_id UUID NOT NULL REFERENCES accounts(id),to_id UUID NOT NULL REFERENCES accounts(id),cents BIGINT NOT NULL CHECK(cents>0),created_at BIGINT NOT NULL)",
"CREATE INDEX IF NOT EXISTS transfers_from_idx ON transfers(from_id,created_at DESC)",
"CREATE INDEX IF NOT EXISTS transfers_to_idx ON transfers(to_id,created_at DESC)",
"CREATE TABLE IF NOT EXISTS issuance(id UUID PRIMARY KEY,target_id UUID NOT NULL REFERENCES accounts(id),cents BIGINT NOT NULL CHECK(cents>0),issued_at BIGINT NOT NULL)",
"CREATE TABLE IF NOT EXISTS admin_events(id UUID PRIMARY KEY,event TEXT NOT NULL,account_id UUID NOT NULL REFERENCES accounts(id),details TEXT NOT NULL,created_at BIGINT NOT NULL)"
];
const hash=s=>createHash('sha256').update(s).digest('hex');
const rand=n=>randomBytes(n).toString('hex');
const norm=s=>String(s??'').trim().toUpperCase();
const validKey=s=>/^[A-Z0-9._-]{5,32}$/.test(s);
const validUuid=s=>/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(s);
const validCents=n=>Number.isSafeInteger(n)&&n>0&&n<=1_000_000_000;
const money=n=>Number(n);
function eq(a,b){const x=Buffer.from(String(a)),y=Buffer.from(String(b));return x.length===y.length&&timingSafeEqual(x,y);}
async function hashPin(pin,salt){return (await pbkdf2(pin,salt,175000,32,'sha256')).toString('hex');}
async function query(sql,args=[]){return pool.query(sql,args);}
async function tx(fn){const c=await pool.connect();try{await c.query('BEGIN');const r=await fn(c);await c.query('COMMIT');return r;}catch(e){await c.query('ROLLBACK');throw e;}finally{c.release();}}
async function authLimit(bucket,max,windowMs,failed=false){
  const now=Date.now(),h=hash(bucket);
  return tx(async c=>{
    const row=(await c.query('SELECT hits,reset_at FROM auth_limits WHERE bucket=$1 FOR UPDATE',[h])).rows[0];
    if(row&&Number(row.reset_at)>now&&row.hits>=max)return false;
    if(failed===true){
      const hits=row&&Number(row.reset_at)>now?row.hits+1:1;
      await c.query('INSERT INTO auth_limits(bucket,hits,reset_at) VALUES($1,$2,$3) ON CONFLICT(bucket) DO UPDATE SET hits=$2,reset_at=$3',[h,hits,now+windowMs]);
    }else if(failed===false&&row){await c.query('DELETE FROM auth_limits WHERE bucket=$1',[h]);}
    return true;
  });
}
const send=(res,payload,status=200)=>{res.writeHead(status,{'content-type':'application/json; charset=utf-8','cache-control':'no-store','x-content-type-options':'nosniff','strict-transport-security':'max-age=31536000','access-control-allow-origin':'*','access-control-allow-headers':'Authorization, Content-Type','access-control-allow-methods':'GET, POST, OPTIONS'});res.end(JSON.stringify(payload));};
const fail=(res,message,status=400)=>send(res,{error:message},status);
async function readBody(req){if(!String(req.headers['content-type']||'').startsWith('application/json'))throw Object.assign(Error('Envie application/json.'),{status:415});let raw='';for await(const chunk of req){raw+=chunk;if(raw.length>8192)throw Object.assign(Error('Requisição muito grande.'),{status:413});}try{const j=JSON.parse(raw);if(!j||typeof j!=='object'||Array.isArray(j))throw Error();return j;}catch{throw Object.assign(Error('JSON inválido.'),{status:400});}}
const tokenOf=req=>/^Bearer [a-f0-9]{64}$/.test(req.headers.authorization||'')?hash(req.headers.authorization.slice(7)):null;
async function userAuth(req){const h=tokenOf(req);if(!h)return null;return (await query("SELECT a.id,a.name,a.account_key,a.balance_cents,a.status,a.created_at FROM sessions s JOIN accounts a ON a.id=s.account_id WHERE s.token_hash=$1 AND s.expires_at>$2 AND a.status='ACTIVE'",[h,Date.now()])).rows[0]||null;}
async function adminAuth(req){const h=tokenOf(req);return !!h&&!!(await query('SELECT token_hash FROM admin_sessions WHERE token_hash=$1 AND expires_at>$2',[h,Date.now()])).rowCount;}
async function keys(id){return (await query('SELECT key FROM account_keys WHERE account_id=$1 ORDER BY created_at,key',[id])).rows.map(x=>x.key);}
async function account(a){return {id:a.id,name:a.name,key:a.account_key,keys:await keys(a.id),balance_cents:money(a.balance_cents),status:a.status};}
async function owner(k){return (await query("SELECT a.* FROM account_keys k JOIN accounts a ON a.id=k.account_id WHERE k.key=$1 AND a.status='ACTIVE'",[k])).rows[0]||null;}
async function session(accountId=null){const token=rand(32),expires_at=Date.now()+(accountId?2592000000:7200000);if(accountId)await query('INSERT INTO sessions(token_hash,account_id,expires_at) VALUES($1,$2,$3)',[hash(token),accountId,expires_at]);else await query('INSERT INTO admin_sessions(token_hash,expires_at) VALUES($1,$2)',[hash(token),expires_at]);return {token,expires_at};}
async function endpoint(req,res){
 const u=new URL(req.url,'https://api.local'),path=u.pathname,method=req.method,ip=String(req.headers['x-forwarded-for']||req.socket.remoteAddress||'unknown').split(',')[0].trim();
 if(method==='OPTIONS')return send(res,{ok:true},204);
 if(path==='/health'&&method==='GET'){await query('SELECT 1');return send(res,{ok:true,product:'Banco Amigos V5',mode:'SIMULACAO',max_keys:3});}
 if(method!=='GET'&&method!=='POST')return fail(res,'Método não permitido.',405);
 const body=method==='POST'?await readBody(req):{};
 if(path==='/register'&&method==='POST'){
   const name=String(body.name??'').trim(),k=norm(body.key),pin=String(body.pin??'');
   if(name.length<2||name.length>45||/[<>\x00-\x1f]/.test(name))return fail(res,'Nome inválido.');
   if(!validKey(k))return fail(res,'Chave inválida: use 5 a 32 caracteres.');
   if(!/^\d{6,12}$/.test(pin))return fail(res,'O PIN deve ter 6 a 12 dígitos.');
   if(!await authLimit('register:'+ip,10,3600000,true))return fail(res,'Limite de cadastros excedido.',429);
   const id=randomUUID(),salt=rand(16),ph=await hashPin(pin,salt),now=Date.now();
   try{await tx(async c=>{await c.query("INSERT INTO accounts(id,name,account_key,pin_salt,pin_hash,balance_cents,created_at,status) VALUES($1,$2,$3,$4,$5,0,$6,'ACTIVE')",[id,name,k,salt,ph,now]);await c.query('INSERT INTO account_keys(key,account_id,created_at) VALUES($1,$2,$3)',[k,id,now]);});}
   catch(e){if(e.code==='23505')return fail(res,'Esta chave já existe.',409);throw e;}
   return send(res,{...await session(id),account:await account({id,name,account_key:k,balance_cents:0,status:'ACTIVE'})},201);
 }
 if(path==='/login'&&method==='POST'){
   const k=norm(body.key),pin=String(body.pin??'');
   if(!validKey(k)||!/^\d{6,12}$/.test(pin))return fail(res,'Chave ou PIN inválidos.',401);
   const bucket='login:'+k;
   if(!await authLimit(bucket,6,900000))return fail(res,'Muitas tentativas de login.',429);
   const a=(await query('SELECT a.* FROM accounts a JOIN account_keys ak ON ak.account_id=a.id WHERE ak.key=$1',[k])).rows[0];
   const computed=await hashPin(pin,a?.pin_salt||rand(16));
   if(!a||!eq(computed,a.pin_hash)||a.status!=='ACTIVE'){await authLimit(bucket,6,900000,true);return fail(res,'Chave ou PIN inválidos.',401);}
   await authLimit(bucket,6,900000);
   return send(res,{...await session(a.id),account:await account(a)});
 }
 if(path.startsWith('/admin/')){
   if(path==='/admin/login'&&method==='POST'){
     const b='admin:'+ip,v=String(body.admin_code??'');
     if(!await authLimit(b,5,900000))return fail(res,'Muitas tentativas.',429);
     if(v.length<16||!eq(hash(v),hash(ADMIN_CODE))){await authLimit(b,5,900000,true);return fail(res,'Código administrativo incorreto.',403);}
     await authLimit(b,5,900000);return send(res,await session());
   }
   if(!await adminAuth(req))return fail(res,'Autenticação administrativa necessária.',401);
   if(path==='/admin/logout'&&method==='POST'){await query('DELETE FROM admin_sessions WHERE token_hash=$1',[tokenOf(req)]);return send(res,{ok:true});}
   if(path==='/admin/accounts'&&method==='GET'){
     const q=String(u.searchParams.get('q')||'').trim();if(q.length>60)return fail(res,'Pesquisa muito longa.');
     const rs=await query("SELECT a.id,a.name,a.account_key,a.balance_cents,a.status,a.created_at,COALESCE((SELECT json_agg(key ORDER BY created_at) FROM account_keys WHERE account_id=a.id),'[]'::json) keys FROM accounts a WHERE a.name ILIKE $1 OR a.id IN(SELECT account_id FROM account_keys WHERE key LIKE $2) ORDER BY a.created_at DESC LIMIT 100",['%'+q+'%','%'+q.toUpperCase()+'%']);
     return send(res,{accounts:rs.rows.map(a=>({...a,balance_cents:money(a.balance_cents)}))});
   }
   if(path==='/admin/stats'&&method==='GET'){
     const s=(await query("SELECT count(*)::int total,count(*) FILTER(WHERE status='ACTIVE')::int active,count(*) FILTER(WHERE status='SUSPENDED')::int suspended,COALESCE(sum(balance_cents),0) circulating_cents FROM accounts")).rows[0];
     const issued=(await query('SELECT COALESCE(sum(cents),0) issued_cents FROM issuance')).rows[0];
     return send(res,{stats:{...s,...issued,circulating_cents:money(s.circulating_cents),issued_cents:money(issued.issued_cents)}});
   }
   if(path==='/admin/account'&&method==='GET'){
     const id=String(u.searchParams.get('id')||'');if(!validUuid(id))return fail(res,'Conta inválida.');
     const a=(await query('SELECT id,name,account_key,balance_cents,status,created_at FROM accounts WHERE id=$1',[id])).rows[0];if(!a)return fail(res,'Conta não encontrada.',404);
     const t=await query('SELECT t.id,t.cents,t.created_at,s.name sender,r.name recipient FROM transfers t JOIN accounts s ON t.from_id=s.id JOIN accounts r ON t.to_id=r.id WHERE t.from_id=$1 OR t.to_id=$1 ORDER BY t.created_at DESC LIMIT 50',[id]);
     const i=await query('SELECT id,cents,issued_at FROM issuance WHERE target_id=$1 ORDER BY issued_at DESC LIMIT 30',[id]);
     return send(res,{account:await account(a),transfers:t.rows.map(x=>({...x,cents:money(x.cents)})),issuance:i.rows.map(x=>({...x,cents:money(x.cents)}))});
   }
   if(path==='/admin/status'&&method==='POST'){
     const id=String(body.account_id||''),status=body.status;
     if(!validUuid(id)||!['ACTIVE','SUSPENDED'].includes(status))return fail(res,'Dados inválidos.');
     const updated=await tx(async c=>{
       const r=await c.query('UPDATE accounts SET status=$1 WHERE id=$2 RETURNING id',[status,id]);
       if(!r.rowCount)return false;
       await c.query('INSERT INTO admin_events(id,event,account_id,details,created_at) VALUES($1,$2,$3,$4,$5)',[randomUUID(),'STATUS',id,status,Date.now()]);
       await c.query('DELETE FROM sessions WHERE account_id=$1',[id]);return true;
     });
     return updated?send(res,{ok:true,status}):fail(res,'Conta não encontrada.',404);
   }
   if(path==='/admin/issue'&&method==='POST'){
     const cents=body.cents,k=norm(body.key),id=String(body.idempotency_key||'');
     if(!validCents(cents)||!validKey(k)||!validUuid(id))return fail(res,'Chave, valor ou identificador inválido.');
     const result=await tx(async c=>{
       const prior=(await c.query('SELECT target_id,cents FROM issuance WHERE id=$1',[id])).rows[0];
       if(prior){const a=(await c.query('SELECT name FROM accounts WHERE id=$1',[prior.target_id])).rows[0];return prior.cents==cents?{ok:true,repeated:true,cents,recipient:a?.name,tx:id}:{error:'Identificador utilizado para outra emissão.',status:409};}
       const recipient=(await c.query('SELECT a.id,a.name FROM account_keys ak JOIN accounts a ON a.id=ak.account_id WHERE ak.key=$1 FOR UPDATE OF a',[k])).rows[0];
       if(!recipient)return {error:'Chave não encontrada.',status:404};
       const up=await c.query('UPDATE accounts SET balance_cents=balance_cents+$1 WHERE id=$2 AND balance_cents<=9000000000000-$1 RETURNING id',[cents,recipient.id]);
       if(!up.rowCount)return {error:'Limite de saldo.',status:409};
       await c.query('INSERT INTO issuance(id,target_id,cents,issued_at) VALUES($1,$2,$3,$4)',[id,recipient.id,cents,Date.now()]);
       await c.query('INSERT INTO admin_events(id,event,account_id,details,created_at) VALUES($1,$2,$3,$4,$5)',[randomUUID(),'ISSUE',recipient.id,JSON.stringify({tx:id,cents}),Date.now()]);
       return {ok:true,cents,recipient:recipient.name,tx:id};
     });
     return result.error?fail(res,result.error,result.status):send(res,result);
   }
   return fail(res,'Rota administrativa inexistente.',404);
 }
 const me=await userAuth(req);if(!me)return fail(res,'Entre na conta.',401);
 if(path==='/logout'&&method==='POST'){await query('DELETE FROM sessions WHERE token_hash=$1',[tokenOf(req)]);return send(res,{ok:true});}
 if(path==='/me'&&method==='GET')return send(res,{account:await account(me)});
 if(path==='/keys'&&method==='GET')return send(res,{keys:await keys(me.id),max_keys:3});
 if(path==='/keys'&&method==='POST'){
   const k=norm(body.key);if(!validKey(k))return fail(res,'Chave inválida.');
   const result=await tx(async c=>{
     await c.query('SELECT id FROM accounts WHERE id=$1 FOR UPDATE',[me.id]);
     const count=Number((await c.query('SELECT count(*) c FROM account_keys WHERE account_id=$1',[me.id])).rows[0].c);
     if(count>=3)return {error:'Limite de três chaves atingido.',status:409};
     const inserted=await c.query('INSERT INTO account_keys(key,account_id,created_at) VALUES($1,$2,$3) ON CONFLICT (key) DO NOTHING RETURNING key',[k,me.id,Date.now()]);
     if(!inserted.rowCount)return {error:'Esta chave já existe.',status:409};
     return {keys:(await c.query('SELECT key FROM account_keys WHERE account_id=$1 ORDER BY created_at,key',[me.id])).rows.map(x=>x.key),max_keys:3};
   });
   return result.error?fail(res,result.error,result.status):send(res,result,201);
 }
 if(path==='/keys/delete'&&method==='POST'){
   const k=norm(body.key);if(!validKey(k))return fail(res,'Chave inválida.');
   if(k===me.account_key)return fail(res,'Não é possível excluir a chave principal.',409);
   const r=await query('DELETE FROM account_keys WHERE key=$1 AND account_id=$2 RETURNING key',[k,me.id]);if(!r.rowCount)return fail(res,'Chave não encontrada.',404);
   return send(res,{keys:await keys(me.id),max_keys:3});
 }
 if(path==='/lookup'&&method==='GET'){
   const k=norm(u.searchParams.get('key'));if(!validKey(k))return fail(res,'Chave inválida.');
   const a=await owner(k);return a?send(res,{valid:true,name:a.name,key:k,self:a.id===me.id}):fail(res,'Chave não encontrada.',404);
 }
 if(path==='/transfer'&&method==='POST'){
   const cents=body.cents,k=norm(body.key),id=String(body.idempotency_key||'');
   if(!validCents(cents)||!validKey(k)||!validUuid(id))return fail(res,'Valor, chave ou identificador inválido.');
   const result=await tx(async c=>{
     const existing=(await c.query('SELECT cents,to_id FROM transfers WHERE id=$1 AND from_id=$2',[id,me.id])).rows[0];
     if(existing){const to=(await c.query('SELECT a.name FROM accounts a WHERE a.id=$1',[existing.to_id])).rows[0];return existing.cents==cents?{ok:true,repeated:true,tx:id,cents,recipient:{name:to?.name,key:k}}:{error:'Identificador repetido com outro valor.',status:409};}
     const to=(await c.query("SELECT a.id,a.name FROM account_keys ak JOIN accounts a ON a.id=ak.account_id WHERE ak.key=$1 AND a.status='ACTIVE'",[k])).rows[0];
     if(!to)return {error:'Chave não encontrada.',status:404};
     if(to.id===me.id)return {error:'Não transfira para a própria conta.',status:409};
     // Serializa a conta do pagador; em seguida bloqueia o recebedor para assegurar atomicidade.
     const locked=await c.query("SELECT id,balance_cents FROM accounts WHERE id=$1 AND status='ACTIVE' FOR UPDATE",[me.id]);
     if(!locked.rowCount)return {error:'Conta bloqueada.',status:403};
     if(Number(locked.rows[0].balance_cents)<cents)return {error:'Saldo insuficiente.',status:409};
     const recipient=await c.query("SELECT id FROM accounts WHERE id=$1 AND status='ACTIVE' FOR UPDATE",[to.id]);
     if(!recipient.rowCount)return {error:'Destinatário bloqueado.',status:409};
     await c.query('UPDATE accounts SET balance_cents=balance_cents-$1 WHERE id=$2',[cents,me.id]);
     await c.query('UPDATE accounts SET balance_cents=balance_cents+$1 WHERE id=$2',[cents,to.id]);
     await c.query('INSERT INTO transfers(id,from_id,to_id,cents,created_at) VALUES($1,$2,$3,$4,$5)',[id,me.id,to.id,cents,Date.now()]);
     return {ok:true,tx:id,cents,recipient:{name:to.name,key:k}};
   });
   return result.error?fail(res,result.error,result.status):send(res,result);
 }
 if(path==='/history'&&method==='GET'){
   const r=await query("SELECT t.id,t.cents,t.created_at,CASE WHEN t.from_id=$1 THEN 'ENVIADO' ELSE 'RECEBIDO' END direction,CASE WHEN t.from_id=$1 THEN rec.name ELSE snd.name END peer_name,CASE WHEN t.from_id=$1 THEN rec.account_key ELSE snd.account_key END peer_key FROM transfers t JOIN accounts snd ON snd.id=t.from_id JOIN accounts rec ON rec.id=t.to_id WHERE t.from_id=$1 OR t.to_id=$1 UNION ALL SELECT i.id,i.cents,i.issued_at,'CREDITO ADMIN','Administração','ADMIN' FROM issuance i WHERE i.target_id=$1 ORDER BY created_at DESC LIMIT 100",[me.id]);
   return send(res,{history:r.rows.map(x=>({...x,cents:money(x.cents)}))});
 }
 return fail(res,'Rota inexistente.',404);
}
async function boot(){
  const c=await pool.connect();try{for(const sql of sqlSchema)await c.query(sql);}finally{c.release();}
  const server=http.createServer((req,res)=>{endpoint(req,res).catch(e=>{console.error('Request failed',e.code||e.message);if(!res.headersSent)fail(res,'Erro interno do servidor.',500);else res.end();});});
  server.listen(PORT,'0.0.0.0',()=>console.log('Banco Amigos V5 ouvindo na porta '+PORT));
}
boot().catch(e=>{console.error('Falha ao iniciar',e.code||e.message);process.exit(1);});
