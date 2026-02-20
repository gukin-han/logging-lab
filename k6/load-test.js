import http from 'k6/http';
import { check } from 'k6';

export const options = {
    vus: 100,
    duration: '1m',
};

export default function () {
    const res = http.get('http://localhost:8080/api/logs/test');
    check(res, {
        'status is 200': (r) => r.status === 200,
    });
}
