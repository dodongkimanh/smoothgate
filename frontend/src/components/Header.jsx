import { useNavigate, useLocation } from 'react-router-dom'
import { useAuthStore } from '../store/authStore'
import {
  Bell,
  Search,
  ChevronDown,
  LogOut,
  Globe,
  Moon,
  Menu,
  User as UserIcon,
} from 'lucide-react'
import { useState, useRef, useEffect } from 'react'

export default function Header({ onMenuToggle }) {
  const navigate = useNavigate()
  const location = useLocation()
  const { user, logout } = useAuthStore()
  const [showUserMenu, setShowUserMenu] = useState(false)
  const menuRef = useRef(null)

  useEffect(() => {
    function handleClick(e) {
      if (menuRef.current && !menuRef.current.contains(e.target)) {
        setShowUserMenu(false)
      }
    }
    document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [])

  const handleLogout = () => {
    logout()
    window.location.href = '/login'
  }

  const getPageTitle = () => {
    const titles = {
      '/dashboard': 'Tổng quan',
      '/campaigns-list': 'Trình quản lý quảng cáo',
      '/ad-accounts': 'Trình quản lý quảng cáo',
      '/orders': 'Đơn hàng',
      '/connect-ads': 'Kết nối tài khoản quảng cáo',
      '/connect-poscake': 'Kết nối Poscake',
      '/settings': 'Cài đặt',
      '/agent': 'Agent AI',
    }
    return titles[location.pathname] || 'SmoothGate'
  }

  return (
    <header className="bg-white/95 backdrop-blur-sm border-b border-slate-200 shadow-sm">
      {/* Top bar */}
      <div className="flex items-center justify-between px-6 py-3">
        <div className="flex items-center gap-3">
          <button
            onClick={onMenuToggle}
            className="w-9 h-9 flex items-center justify-center rounded-lg hover:bg-gray-50 text-gray-500 lg:hidden"
          >
            <Menu size={20} />
          </button>
          <h1 className="text-lg font-semibold text-gray-800">{getPageTitle()}</h1>
        </div>

        <div className="flex items-center gap-3">
          {/* User */}
          <div className="relative" ref={menuRef}>
            <button
              onClick={() => setShowUserMenu(!showUserMenu)}
              className="flex items-center gap-2 px-2 py-1.5 rounded-lg hover:bg-gray-50 transition-colors"
            >
              <div className="w-8 h-8 bg-blue-500 rounded-full flex items-center justify-center">
                <UserIcon size={16} className="text-white" />
              </div>
              <span className="text-sm font-medium text-gray-700">
                {user?.fullName || 'User'}
              </span>
              <ChevronDown size={14} className="text-gray-400" />
            </button>

            {showUserMenu && (
              <div className="absolute right-0 top-full mt-2 w-48 bg-white rounded-xl shadow-xl border border-gray-100 py-1 z-50 animate-fade-in">
                <button
                  onClick={handleLogout}
                  className="w-full flex items-center gap-3 px-4 py-2.5 text-sm text-red-600 hover:bg-red-50 transition-colors"
                >
                  <LogOut size={16} />
                  Đăng xuất
                </button>
              </div>
            )}
          </div>
        </div>
      </div>

    </header>
  )
}
