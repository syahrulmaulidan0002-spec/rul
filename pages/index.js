import Head from 'next/head'
import AttendanceForm from '../components/AttendanceForm'

export default function Home() {
  return (
    <>
      <Head>
        <title>RUL - Absensi</title>
        <meta name="viewport" content="width=device-width, initial-scale=1" />
      </Head>
      <main style={{minHeight:'100vh', display:'flex', alignItems:'center', justifyContent:'center', padding:'2rem', background:'#0F0F0F'}}>
        <div style={{maxWidth:900, width:'100%'}}>
          <h1 style={{fontFamily:'Poppins, sans-serif', color:'#fff', textAlign:'center', marginBottom:'1rem'}}>RUL - Website Absensi (Firebase)</h1>
          <AttendanceForm />
        </div>
      </main>
    </>
  )
}
