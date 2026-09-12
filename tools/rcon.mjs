// tools/rcon.mjs — 最小 RCON 客户端（Source RCON 协议）
// 用法：node tools/rcon.mjs <host> <port> <password> <command...>
import net from 'node:net';

const [host, port, password, ...cmdParts] = process.argv.slice(2);
const command = cmdParts.join(' ');

function packet(id, type, body) {
  const bodyBuf = Buffer.from(body, 'utf8');
  const buf = Buffer.alloc(4 + 4 + 4 + bodyBuf.length + 2);
  buf.writeInt32LE(4 + 4 + bodyBuf.length + 2, 0);
  buf.writeInt32LE(id, 4);
  buf.writeInt32LE(type, 8);
  bodyBuf.copy(buf, 12);
  buf.writeInt8(0, 12 + bodyBuf.length);
  buf.writeInt8(0, 13 + bodyBuf.length);
  return buf;
}

const sock = net.createConnection({ host, port: Number(port) }, () => {
  sock.write(packet(1, 3, password));   // auth
});
let stage = 'auth';
sock.on('data', (d) => {
  const id = d.readInt32LE(4);
  if (stage === 'auth') {
    if (id === -1) { console.error('rcon 鉴权失败'); process.exit(1); }
    stage = 'cmd';
    sock.write(packet(2, 2, command));
  } else {
    console.log(d.toString('utf8', 12, d.length - 2));
    process.exit(0);
  }
});
setTimeout(() => { console.error('rcon 超时'); process.exit(1); }, 8000);
