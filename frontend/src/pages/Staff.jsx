import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import toast from 'react-hot-toast'
import { Lock, Pencil, Plus, Trash2, Unlock, UserCog, X } from 'lucide-react'
import { createUser, deleteUser, getSelectedAdAccounts, getUsers, updateUser } from '../services/api'
import { useAuthStore } from '../store/authStore'

const ROLE_LABELS = { ADMIN: 'Quản trị viên', ANALYST: 'Quản trị viên', STAFF: 'Nhân viên' }

const errorMessage = (err, fallback) => err?.response?.data?.message || fallback

export default function Staff() {
  const queryClient = useQueryClient()
  const currentEmail = useAuthStore((s) => s.user?.email)
  const [editing, setEditing] = useState(null) // null = closed, {} = new user, user object = edit

  const { data: users = [], isLoading } = useQuery({
    queryKey: ['users'],
    queryFn: () => getUsers().then((r) => r.data?.data || []),
  })
  const { data: accounts = [] } = useQuery({
    queryKey: ['meta-selected-accounts'],
    queryFn: () => getSelectedAdAccounts().then((r) => r.data?.data || []),
  })
  const accountName = useMemo(
    () => Object.fromEntries(accounts.map((a) => [a.id, a.name || a.externalAccountId])),
    [accounts],
  )

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['users'] })

  const toggleStatus = useMutation({
    mutationFn: (u) => updateUser(u.id, { status: u.status === 'INACTIVE' ? 'ACTIVE' : 'INACTIVE' }),
    onSuccess: refresh,
    onError: (err) => toast.error(errorMessage(err, 'Không cập nhật được')),
  })
  const remove = useMutation({
    mutationFn: (u) => deleteUser(u.id),
    onSuccess: () => {
      toast.success('Đã xóa tài khoản')
      refresh()
    },
    onError: (err) => toast.error(errorMessage(err, 'Không xóa được')),
  })

  return (
    <div className="space-y-4">
      <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-4 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-base font-semibold text-slate-800 flex items-center gap-2">
            <UserCog size={18} /> Quản lý nhân viên
          </h1>
          <p className="text-xs text-slate-500 mt-1">
            Nhân viên chỉ vào được Trình quản lý QC và chỉ thấy các tài khoản quảng cáo được giao. Họ được bật/tắt và sửa ngân sách trên các tài khoản đó.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setEditing({})}
          className="px-3 py-2 rounded-lg text-sm font-medium bg-blue-500 text-white hover:bg-blue-600 flex items-center gap-1.5"
        >
          <Plus size={16} /> Thêm nhân viên
        </button>
      </div>

      <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 text-xs uppercase text-slate-500">
            <tr>
              <th className="text-left px-4 py-2.5 font-medium">Họ tên</th>
              <th className="text-left px-4 py-2.5 font-medium">Email</th>
              <th className="text-left px-4 py-2.5 font-medium">Vai trò</th>
              <th className="text-left px-4 py-2.5 font-medium">Tài khoản QC được giao</th>
              <th className="text-left px-4 py-2.5 font-medium">Trạng thái</th>
              <th className="px-4 py-2.5" />
            </tr>
          </thead>
          <tbody>
            {isLoading && (
              <tr><td colSpan={6} className="px-4 py-6 text-center text-slate-400">Đang tải...</td></tr>
            )}
            {users.map((u) => {
              const isStaff = u.role === 'STAFF'
              const isSelf = u.email === currentEmail
              const inactive = u.status === 'INACTIVE'
              return (
                <tr key={u.id} className={`border-t border-slate-100 ${inactive ? 'opacity-60' : ''}`}>
                  <td className="px-4 py-2.5 text-slate-800">{u.fullName}{isSelf && <span className="text-xs text-slate-400"> (bạn)</span>}</td>
                  <td className="px-4 py-2.5 text-slate-600">{u.email}</td>
                  <td className="px-4 py-2.5">
                    <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${isStaff ? 'bg-slate-100 text-slate-700' : 'bg-blue-50 text-blue-700'}`}>
                      {ROLE_LABELS[u.role] || u.role}
                    </span>
                  </td>
                  <td className="px-4 py-2.5 text-xs text-slate-600">
                    {!isStaff ? (
                      <span className="text-slate-400">Tất cả</span>
                    ) : u.adAccountIds.length === 0 ? (
                      <span className="text-amber-600">Chưa giao tài khoản nào</span>
                    ) : (
                      <div className="flex flex-wrap gap-1">
                        {u.adAccountIds.map((id) => (
                          <span key={id} className="px-1.5 py-0.5 rounded bg-slate-100">{accountName[id] || `#${id}`}</span>
                        ))}
                      </div>
                    )}
                  </td>
                  <td className="px-4 py-2.5 text-xs">
                    {inactive ? <span className="text-red-600">Đã khóa</span> : <span className="text-emerald-600">Hoạt động</span>}
                  </td>
                  <td className="px-4 py-2.5">
                    <div className="flex justify-end gap-1">
                      <IconButton title="Sửa" onClick={() => setEditing(u)}><Pencil size={15} /></IconButton>
                      {!isSelf && (
                        <>
                          <IconButton
                            title={inactive ? 'Mở khóa' : 'Khóa'}
                            onClick={() => toggleStatus.mutate(u)}
                          >
                            {inactive ? <Unlock size={15} /> : <Lock size={15} />}
                          </IconButton>
                          <IconButton
                            title="Xóa"
                            danger
                            onClick={() => {
                              if (window.confirm(`Xóa tài khoản ${u.email}?`)) remove.mutate(u)
                            }}
                          >
                            <Trash2 size={15} />
                          </IconButton>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      {editing && (
        <UserModal
          user={editing.id ? editing : null}
          isSelf={editing.email === currentEmail}
          accounts={accounts}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            refresh()
          }}
        />
      )}
    </div>
  )
}

function IconButton({ children, title, onClick, danger = false }) {
  return (
    <button
      type="button"
      title={title}
      onClick={onClick}
      className={`p-1.5 rounded-lg text-slate-500 ${danger ? 'hover:bg-red-50 hover:text-red-600' : 'hover:bg-slate-100 hover:text-slate-700'}`}
    >
      {children}
    </button>
  )
}

function UserModal({ user, isSelf, accounts, onClose, onSaved }) {
  const isNew = !user
  const [fullName, setFullName] = useState(user?.fullName || '')
  const [email, setEmail] = useState(user?.email || '')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState(user?.role === 'STAFF' || isNew ? 'STAFF' : 'ADMIN')
  const [accountIds, setAccountIds] = useState(new Set(user?.adAccountIds || []))

  const save = useMutation({
    mutationFn: () => {
      const payload = { fullName, role, adAccountIds: role === 'STAFF' ? [...accountIds] : [] }
      if (password) payload.password = password
      return isNew ? createUser({ ...payload, email, password }) : updateUser(user.id, payload)
    },
    onSuccess: () => {
      toast.success(isNew ? 'Đã tạo tài khoản' : 'Đã lưu thay đổi')
      onSaved()
    },
    onError: (err) => toast.error(errorMessage(err, 'Không lưu được')),
  })

  const toggleAccount = (id) => {
    setAccountIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  return (
    <div className="fixed inset-0 z-50 bg-black/40 flex items-center justify-center p-4" onClick={onClose}>
      <div
        className="bg-white rounded-xl shadow-xl w-full max-w-lg max-h-[90vh] flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-5 py-3 border-b border-slate-100">
          <h2 className="font-semibold text-slate-800">{isNew ? 'Thêm nhân viên' : 'Sửa tài khoản'}</h2>
          <button type="button" onClick={onClose} className="p-1 rounded hover:bg-slate-100"><X size={18} /></button>
        </div>

        <form
          className="flex-1 overflow-y-auto px-5 py-4 space-y-3"
          onSubmit={(e) => {
            e.preventDefault()
            save.mutate()
          }}
        >
          <Field label="Họ tên">
            <input value={fullName} onChange={(e) => setFullName(e.target.value)} className={inputClass} />
          </Field>
          <Field label="Email đăng nhập">
            <input
              type="email"
              required
              disabled={!isNew}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className={`${inputClass} disabled:bg-slate-50 disabled:text-slate-500`}
            />
          </Field>
          <Field label={isNew ? 'Mật khẩu (ít nhất 8 ký tự)' : 'Mật khẩu mới (để trống nếu không đổi)'}>
            <input
              type="password"
              required={isNew}
              minLength={8}
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className={inputClass}
            />
          </Field>
          <Field label="Vai trò">
            <select
              value={role}
              disabled={isSelf}
              onChange={(e) => setRole(e.target.value)}
              className={`${inputClass} disabled:bg-slate-50`}
            >
              <option value="STAFF">Nhân viên — chỉ xem tài khoản được giao</option>
              <option value="ADMIN">Quản trị viên — toàn quyền</option>
            </select>
          </Field>

          {role === 'STAFF' && (
            <Field label={`Tài khoản quảng cáo được giao (${accountIds.size}/${accounts.length})`}>
              <div className="flex gap-2 mb-2">
                <button type="button" className={chipClass} onClick={() => setAccountIds(new Set(accounts.map((a) => a.id)))}>Chọn tất cả</button>
                <button type="button" className={chipClass} onClick={() => setAccountIds(new Set())}>Bỏ chọn</button>
              </div>
              <div className="border border-slate-200 rounded-lg max-h-60 overflow-y-auto divide-y divide-slate-100">
                {accounts.map((a) => (
                  <label key={a.id} className="flex items-center gap-2 px-3 py-2 text-sm cursor-pointer hover:bg-slate-50">
                    <input type="checkbox" checked={accountIds.has(a.id)} onChange={() => toggleAccount(a.id)} className="w-4 h-4" />
                    <span className="text-slate-800">{a.name || a.externalAccountId}</span>
                    <span className="text-xs text-slate-400 ml-auto">{a.externalAccountId}</span>
                  </label>
                ))}
                {accounts.length === 0 && <p className="px-3 py-2 text-xs text-slate-400">Chưa có tài khoản quảng cáo nào được kết nối</p>}
              </div>
            </Field>
          )}

          <div className="flex justify-end gap-2 pt-2">
            <button type="button" onClick={onClose} className="px-4 py-2 rounded-lg text-sm border border-slate-200 text-slate-700">Hủy</button>
            <button
              type="submit"
              disabled={save.isPending}
              className="px-4 py-2 rounded-lg text-sm font-medium bg-blue-500 text-white hover:bg-blue-600 disabled:opacity-60"
            >
              {save.isPending ? 'Đang lưu...' : isNew ? 'Tạo tài khoản' : 'Lưu'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

const inputClass = 'w-full px-3 py-2 border border-slate-200 rounded-lg text-sm focus:outline-none focus:border-blue-400'
const chipClass = 'px-2.5 py-1 rounded-lg text-xs border border-slate-200 text-slate-700 hover:bg-slate-50'

function Field({ label, children }) {
  return (
    <div>
      <label className="block text-xs font-medium text-slate-600 mb-1">{label}</label>
      {children}
    </div>
  )
}
