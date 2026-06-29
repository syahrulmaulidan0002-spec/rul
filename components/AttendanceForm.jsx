import { useState, useEffect } from 'react'
import { getFirestore, collection, addDoc, query, where, onSnapshot, serverTimestamp, orderBy } from 'firebase/firestore'
import { getAuth } from 'firebase/auth'
import firebaseApp from '../lib/firebase'

export default function AttendanceForm(){
  const db = getFirestore(firebaseApp)
  const auth = getAuth(firebaseApp)
  const [note, setNote] = useState('')
  const [records, setRecords] = useState([])
  const [user, setUser] = useState(null)

  useEffect(()=>{
    const unsubscribeAuth = auth.onAuthStateChanged(u => setUser(u))
    return () => unsubscribeAuth()
  }, [])

  useEffect(()=>{
    if(!user) { setRecords([]); return }
    const q = query(collection(db,'attendances'), where('userId','==', user.uid), orderBy('createdAt','desc'))
    const unsub = onSnapshot(q, snap => {
      const arr = snap.docs.map(d => ({ id: d.id, ...d.data() }))
      setRecords(arr)
    })
    return () => unsub()
  }, [user])

  async function handleCheckIn(){
    if(!user) return alert('Silakan login terlebih dahulu')
    try{
      await addDoc(collection(db,'attendances'), {
        userId: user.uid,
        fullName: user.displayName || user.email,
        date: new Date().toISOString().slice(0,10),
        checkIn: serverTimestamp(),
        checkOut: null,
        status: 'Hadir',
        note: note || '',
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      })
      setNote('')
    }catch(e){
      console.error(e)
      alert('Gagal menyimpan. Cek console.')
    }
  }

  return (
    <div className="card">
      <h3 style={{fontSize:18, fontWeight:600}}>Absensi Hari Ini</h3>
      <textarea value={note} onChange={e=>setNote(e.target.value)} placeholder="Catatan (opsional)" style={{width:'100%', marginTop:12, padding:12, borderRadius:12, border:'1px solid rgba(255,255,255,0.06)', background:'transparent', color:'#fff'}} />
      <div style={{marginTop:12}}>
        <button onClick={handleCheckIn} className="glass-btn">Check In Sekarang</button>
      </div>

      <div style={{marginTop:16}}>
        <h4 style={{fontSize:13, opacity:0.85}}>Riwayat singkat (realtime)</h4>
        <ul style={{marginTop:8}}>
          {records.map(r => (
            <li key={r.id} style={{padding:'8px 0', borderBottom:'1px solid rgba(255,255,255,0.03)'}}>{r.fullName} — {r.date} — {r.status}</li>
          ))}
        </ul>
      </div>
    </div>
  )
}
