import { Component } from 'react'
import { AlertTriangle, RefreshCw } from 'lucide-react'

export default class ErrorBoundary extends Component {
  constructor(props) {
    super(props)
    this.state = { hasError: false, error: null }
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, error }
  }

  componentDidCatch(error, info) {
    console.error('ErrorBoundary caught:', error, info)
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex flex-col items-center justify-center min-h-[60vh] gap-4 p-8 text-center">
          <div className="w-16 h-16 bg-red-50 rounded-2xl flex items-center justify-center">
            <AlertTriangle size={28} className="text-red-500" />
          </div>
          <div>
            <h2 className="text-lg font-semibold text-gray-800">Đã xảy ra lỗi</h2>
            <p className="text-sm text-gray-500 mt-1 max-w-sm">
              {this.state.error?.message || 'Trang này gặp sự cố khi tải. Vui lòng thử lại.'}
            </p>
          </div>
          <button
            onClick={() => this.setState({ hasError: false, error: null })}
            className="btn-primary"
          >
            <RefreshCw size={16} />
            Thử lại
          </button>
        </div>
      )
    }
    return this.props.children
  }
}
