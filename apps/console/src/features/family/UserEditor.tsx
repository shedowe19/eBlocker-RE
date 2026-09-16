// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useState } from 'react';
import type { ConsoleClient } from '../../api/client';
import type { Locale } from '../../i18n';
import type { FamilyData, FamilyDevice, FamilyUser } from './contracts';
import { assignDevice, saveUser } from './api';
import { EditorFrame, FormValidation } from './EditorFrame';
import type { Mutate } from './EditorFrame';
import { editMessages } from './editMessages';
import { familyMessages } from './messages';
import { calendarDate, profileName, userName } from './presentation';

type EditorProps = {
    client: ConsoleClient;
    data: FamilyData;
    locale: Locale;
    mutate: Mutate;
    onClose: () => void;
};
export function UserEditor({
    client,
    data,
    locale,
    mutate,
    onClose,
    user,
}: EditorProps & { user?: FamilyUser }) {
    const t = editMessages[locale];
    const f = familyMessages[locale];
    const [name, setName] = useState(user?.name ?? '');
    const [role, setRole] = useState<NonNullable<FamilyUser['userRole']>>(
        user?.userRole ?? 'CHILD',
    );
    const [birthday, setBirthday] = useState(user?.birthday ? calendarDate(user.birthday) : '');
    const [profileId, setProfileId] = useState(user?.associatedProfileId?.toString() ?? '');
    const today = new Date();
    const todayValue = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
    const profiles = data.profiles.filter(
        (profile) =>
            !profile.hidden &&
            (!profile.forSingleUser ||
                profile.id === user?.associatedProfileId ||
                !data.users.some((owner) => owner.associatedProfileId === profile.id)),
    );
    return (
        <EditorFrame
            title={user ? t.editUser : t.addUser}
            locale={locale}
            onClose={onClose}
            onSave={async () => {
                if (!name.trim() || name.trim().length > 16)
                    throw new FormValidation(t.nameInvalid);
                const date = birthday
                    ? (birthday.split('-').map(Number) as [number, number, number])
                    : null;
                if (
                    date &&
                    (!/^\d{4}-\d{2}-\d{2}$/.test(birthday) ||
                        birthday > todayValue ||
                        new Date(`${birthday}T12:00:00Z`).toISOString().slice(0, 10) !== birthday)
                )
                    throw new FormValidation(t.birthdayInvalid);
                if (!profileId || !profiles.some((profile) => profile.id === Number(profileId)))
                    throw new FormValidation(t.profileRequired);
                return mutate(() =>
                    saveUser(client, user, {
                        name: name.trim(),
                        userRole: role,
                        birthday: date,
                        associatedProfileId: Number(profileId),
                    }),
                );
            }}
        >
            <label>
                {t.name}
                <input
                    autoComplete="off"
                    maxLength={16}
                    value={name}
                    onChange={(event) => setName(event.target.value)}
                    required
                />
            </label>
            <label>
                {t.role}
                <select
                    value={role}
                    onChange={(event) => setRole(event.target.value as typeof role)}
                >
                    <option value="CHILD">{f.child}</option>
                    <option value="PARENT">{f.parent}</option>
                    <option value="OTHER">{f.other}</option>
                </select>
            </label>
            <label>
                {f.birthday}
                <input
                    type="date"
                    value={birthday}
                    max={todayValue}
                    onChange={(event) => setBirthday(event.target.value)}
                />
            </label>
            <label>
                {f.profile}
                <select
                    value={profileId}
                    onChange={(event) => setProfileId(event.target.value)}
                    required
                >
                    <option value="">{t.chooseProfile}</option>
                    {profiles.map((profile) => (
                        <option key={profile.id} value={profile.id}>
                            {profileName(profile, locale)}
                        </option>
                    ))}
                </select>
            </label>
            <p className="family-muted">{t.profileHelp}</p>
        </EditorFrame>
    );
}

export function PinEditor({
    client,
    locale,
    mutate,
    onClose,
    user,
}: Omit<EditorProps, 'data'> & { user: FamilyUser }) {
    const t = editMessages[locale];
    const [pin, setPin] = useState('');
    const [repeat, setRepeat] = useState('');
    const [remove, setRemove] = useState(false);
    useEffect(() => {
        setPin('');
        setRepeat('');
        setRemove(false);
    }, [locale]);
    return (
        <EditorFrame
            title={`${t.pin}: ${userName(user, locale)}`}
            locale={locale}
            onClose={onClose}
            submitLabel={remove ? t.removePin : t.setPin}
            onSave={async () => {
                const secret = pin;
                setPin('');
                setRepeat('');
                if (!remove && (secret.length < 4 || secret.length > 16 || secret !== repeat))
                    throw new FormValidation(t.pinInvalid);
                return mutate(() =>
                    remove
                        ? client.deleteVoid(`/users/${user.id}/pin`)
                        : client.postVoid(`/users/${user.id}/pin`, { newPin: secret }),
                );
            }}
        >
            <p>
                {user.containsPin === undefined
                    ? t.pinUnknown
                    : user.containsPin
                      ? t.pinPresent
                      : t.pinAbsent}
            </p>
            <p className="family-muted">{t.pinHelp}</p>
            <label>
                {t.newPin}
                <input
                    type="password"
                    autoComplete="new-password"
                    minLength={4}
                    maxLength={16}
                    disabled={remove}
                    value={pin}
                    onChange={(event) => setPin(event.target.value)}
                />
            </label>
            <label>
                {t.repeatPin}
                <input
                    type="password"
                    autoComplete="new-password"
                    maxLength={16}
                    disabled={remove}
                    value={repeat}
                    onChange={(event) => setRepeat(event.target.value)}
                />
            </label>
            {user.containsPin !== false && (
                <label className="family-check">
                    <input
                        type="checkbox"
                        checked={remove}
                        onChange={(event) => {
                            setRemove(event.target.checked);
                            setPin('');
                            setRepeat('');
                        }}
                    />
                    {t.removePinConfirm}
                </label>
            )}
        </EditorFrame>
    );
}

export function AssignmentEditor({
    client,
    data,
    locale,
    mutate,
    onClose,
    device,
}: EditorProps & { device: FamilyDevice }) {
    const t = editMessages[locale];
    const f = familyMessages[locale];
    const users = data.users.filter((user) => !user.system);
    const [selected, setSelected] = useState(
        users.some((user) => user.id === device.assignedUser) ? String(device.assignedUser) : '',
    );
    const [confirmed, setConfirmed] = useState(false);
    return (
        <EditorFrame
            title={`${t.assign}: ${device.name || device.ipAddresses[0] || f.unnamedDevice}`}
            locale={locale}
            onClose={onClose}
            onSave={async () => {
                if (!confirmed) throw new FormValidation(t.assignConfirm);
                return mutate(() =>
                    assignDevice(client, device, selected === '' ? null : Number(selected)),
                );
            }}
        >
            <p>{t.assignHelp}</p>
            <label>
                {f.assigned}
                <select
                    value={selected}
                    onChange={(event) => {
                        setSelected(event.target.value);
                        setConfirmed(false);
                    }}
                >
                    <option value="">{f.standardUser}</option>
                    {users.map((user) => (
                        <option key={user.id} value={user.id}>
                            {userName(user, locale)}
                        </option>
                    ))}
                </select>
            </label>
            <label className="family-check">
                <input
                    type="checkbox"
                    checked={confirmed}
                    onChange={(event) => setConfirmed(event.target.checked)}
                />
                {t.assignConfirm}
            </label>
        </EditorFrame>
    );
}
