import './App.css'
import { BackendStatus } from './components/BackendStatus.tsx'
import { PcBuilderPage } from './features/builder/PcBuilderPage.tsx'
import { PcDetailPage } from './features/pc/pages/PcDetailPage.tsx'
import { PcEditPage, PcNewPage } from './features/pc/pages/PcFormPages.tsx'
import { PcListPage } from './features/pc/pages/PcListPage.tsx'
import { paths, useRoute, type Route } from './lib/router.ts'

// 자동 인식 패널(PcScanPanel)은 PC 등록·수정 폼 안에서 onApply로 연결한다.
function Page({ route }: { route: Route }) {
  switch (route.name) {
    case 'list':
      return <PcListPage />
    case 'build':
      return <PcBuilderPage />
    case 'new':
      return <PcNewPage />
    case 'detail':
      return <PcDetailPage key={route.id} id={route.id} />
    case 'edit':
      return <PcEditPage key={route.id} id={route.id} />
    case 'notFound':
      return (
        <div className="empty">
          <p>페이지를 찾을 수 없습니다.</p>
          <a href={paths.list()}>내 PC 목록으로</a>
        </div>
      )
  }
}

function App() {
  const route = useRoute()

  return (
    <>
      <header className="app-header">
        <a href={paths.list()} className="app-title">
          PC 업그레이드 실험실
        </a>
        <nav className="app-nav">
          <a href={paths.list()}>내 PC</a>
          <a href={paths.build()}>PC 구성하기</a>
        </nav>
      </header>
      <main className="app-main">
        <Page route={route} />
      </main>
      <footer className="app-footer">
        <BackendStatus />
      </footer>
    </>
  )
}

export default App
